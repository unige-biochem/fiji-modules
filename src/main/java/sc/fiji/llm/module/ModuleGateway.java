package sc.fiji.llm.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.awt.Dialog;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.scijava.Cancelable;
import org.scijava.Context;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogListener;
import org.scijava.log.LogMessage;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleService;
import org.scijava.module.process.ModulePostprocessor;
import org.scijava.module.process.ModulePreprocessor;
import org.scijava.module.process.PostprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.object.ObjectService;
import org.scijava.plugin.PluginService;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.service.Service;
import org.scijava.util.POM;

import ch.unige.biochem.scijava.introspect.CommandIntrospector;

/**
 * Search, describe and run any SciJava module - a command or a script file - on behalf of an agent.
 * <p>
 * No LLM dependency: adapters (an MCP tool plugin, a command line) sit on top of it.
 * <p>
 * Agent semantics of {@link #run}: never prompt and never reuse remembered values. An input is
 * given, or keeps its declared default, or - for an object type with exactly one live instance in
 * the {@link ObjectService} - is bound to that instance; a required input with none of these is an
 * error. Object inputs and outputs are exchanged by name through the ObjectService, which is the
 * registry of live objects (sessions, images, viewers...). Errors carry their causes and the
 * warnings and errors logged while the module ran.
 */
public class ModuleGateway {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	/** Processors left out of agent runs: dialogs, remembered values, output windows. */
	private static final String EXCLUDED_PROCESSORS = "(?i).*(Harvester|LoadInputs|SaveInputs|Display).*";
	private static final int MAX_IMAGE_SIDE = 1024;

	private final Context context;
	private final ModuleService moduleService;
	private final ObjectService objectService;
	private final ConvertService convertService;
	private final LogService logService;
	private final Map<String, Job> jobs = new ConcurrentHashMap<>();

	public ModuleGateway(Context context) {
		this.context = context;
		this.moduleService = context.getService(ModuleService.class);
		this.objectService = context.getService(ObjectService.class);
		this.convertService = context.getService(ConvertService.class);
		this.logService = context.getService(LogService.class);
	}

	/** The result of a run: JSON for the agent, plus the images it produced. */
	public static class Result {
		public final JsonObject json;
		public final List<BufferedImage> images;
		Result(JsonObject json, List<BufferedImage> images) { this.json = json; this.images = images; }
		public String text() { return GSON.toJson(json); }
		public boolean isError() { return json.has("status") && "error".equals(json.get("status").getAsString()); }
	}

	// -- search --

	/** Modules whose title, menu path, description or class match all the words of the query. */
	public String search(String query, int limit) {
		String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Object[]> scored = new ArrayList<>();
		for (ModuleInfo info : moduleService.getModules()) {
			String title = (info.getTitle() + " " + info.getMenuPath()).toLowerCase(Locale.ROOT);
			String rest = (info.getDescription() + " " + info.getDelegateClassName()).toLowerCase(Locale.ROOT);
			int score = 0;
			for (String w : words) {
				if (title.contains(w)) score += 3;
				else if (rest.contains(w)) score += 1;
				else { score = -1; break; }
			}
			if (score > 0) scored.add(new Object[] { score, info });
		}
		scored.sort((a, b) -> Integer.compare((Integer) b[0], (Integer) a[0]));
		JsonArray hits = new JsonArray();
		for (Object[] s : scored.subList(0, Math.min(limit, scored.size()))) {
			ModuleInfo info = (ModuleInfo) s[1];
			JsonObject hit = new JsonObject();
			hit.addProperty("id", idOf(info));
			hit.addProperty("title", info.getTitle());
			if (info.getDescription() != null && !info.getDescription().isEmpty()) hit.addProperty("description", firstSentence(info.getDescription()));
			hits.add(hit);
		}
		return GSON.toJson(hits);
	}

	// -- describe --

	/** Inputs and outputs of a module, after its initializers ran, plus the live objects that fit its object inputs. */
	public String describe(String id) {
		ModuleInfo info = resolve(id);
		JsonObject description = null;
		if (info instanceof CommandInfo) {
			try {
				@SuppressWarnings("unchecked")
				Class<? extends Command> c = (Class<? extends Command>) ((CommandInfo) info).loadClass();
				description = JsonParser.parseString(CommandIntrospector.describeInitialized(context, c, Collections.emptyMap())).getAsJsonObject();
			}
			catch (Throwable t) {
				logService.debug("Falling back to a plain description of " + id, t);
			}
		}
		if (description == null) description = plainDescription(info);
		description.addProperty("id", idOf(info));
		String origin = origin(info);
		if (origin != null) description.addProperty("provided_by", origin);
		JsonObject candidates = new JsonObject();
		for (ModuleItem<?> item : info.inputs()) {
			if (isSimple(item.getType()) || isService(item.getType())) continue;
			JsonArray names = new JsonArray();
			for (Object o : liveObjects(item.getType())) names.add(nameOf(o));
			candidates.add(item.getName(), names);
		}
		if (candidates.size() > 0) description.add("live_objects", candidates);
		return GSON.toJson(description);
	}

	/** groupId:artifactId:version of the jar a command comes from, so that an agent knows which version it is using. */
	private String origin(ModuleInfo info) {
		if (!(info instanceof CommandInfo)) return null;
		try {
			POM pom = POM.getPOM(((CommandInfo) info).loadClass());
			return pom == null ? null : pom.getGroupId() + ":" + pom.getArtifactId() + ":" + pom.getVersion();
		}
		catch (Throwable t) {
			return null;
		}
	}

	private JsonObject plainDescription(ModuleInfo info) {
		JsonObject d = new JsonObject();
		d.addProperty("title", info.getTitle());
		if (info.getDescription() != null) d.addProperty("description", info.getDescription());
		JsonArray in = new JsonArray();
		for (ModuleItem<?> item : info.inputs()) {
			if (isService(item.getType()) || item.getVisibility() == ItemVisibility.MESSAGE) continue;
			JsonObject n = new JsonObject();
			n.addProperty("name", item.getName());
			n.addProperty("type", item.getType().getSimpleName());
			if (item.getLabel() != null && !item.getLabel().isEmpty()) n.addProperty("label", item.getLabel());
			if (item.getDescription() != null && !item.getDescription().isEmpty()) n.addProperty("description", item.getDescription());
			if (item.getDefaultValue() != null) n.addProperty("default", String.valueOf(item.getDefaultValue()));
			if (item.getChoices() != null && !item.getChoices().isEmpty()) n.add("choices", GSON.toJsonTree(item.getChoices()));
			if (!item.isRequired()) n.addProperty("required", false);
			in.add(n);
		}
		d.add("input", in);
		JsonArray out = new JsonArray();
		for (ModuleItem<?> item : info.outputs()) {
			JsonObject n = new JsonObject();
			n.addProperty("name", item.getName());
			n.addProperty("type", item.getType().getSimpleName());
			out.add(n);
		}
		d.add("output", out);
		return d;
	}

	// -- objects --

	/** The live objects, optionally of one type (simple or fully qualified class name). */
	public String objects(String type) {
		JsonArray list = new JsonArray();
		for (Object o : objectService.getObjects(Object.class)) {
			if (o == null || o instanceof Job) continue;
			if (type != null && !type.isEmpty() && !isA(o.getClass(), type)) continue;
			JsonObject n = new JsonObject();
			n.addProperty("name", nameOf(o));
			n.addProperty("type", o.getClass().getSimpleName());
			list.add(n);
		}
		return GSON.toJson(list);
	}

	// -- run --

	/**
	 * Runs a module with JSON inputs, and waits for it up to {@code waitSeconds}. A run still going
	 * after that - or stopped by a dialog - is reported with a job id, to follow with {@link #status}.
	 */
	public Result run(String id, String inputsJson, double waitSeconds) {
		Job job = new Job();
		try {
			ModuleInfo info = resolve(id);
			job.info = info;
			Module module = moduleService.createModule(info);
			if (module instanceof ScriptModule) ((ScriptModule) module).setErrorWriter(job.scriptErrors);
			Map<String, Object> inputs = bindInputs(module, inputsJson == null || inputsJson.isBlank() ? new JsonObject() : JsonParser.parseString(inputsJson).getAsJsonObject(), job);
			job.objectsBefore = identitySet(objectService.getObjects(Object.class));
			job.dialogsBefore = identitySet(java.util.Arrays.asList((Object[]) Window.getWindows()));
			logService.addLogListener(job);
			job.started = System.currentTimeMillis();
			job.future = moduleService.run(module, this.<ModulePreprocessor>processors(PreprocessorPlugin.class), this.<ModulePostprocessor>processors(PostprocessorPlugin.class), inputs);
		}
		catch (Throwable t) {
			job.failure = t;
		}
		job.id = UUID.randomUUID().toString().substring(0, 8);
		jobs.put(job.id, job);
		return await(job, waitSeconds);
	}

	/** Follows a run started by {@link #run}; an empty id lists the runs. */
	public Result status(String jobId, double waitSeconds) {
		if (jobId == null || jobId.isEmpty()) {
			JsonObject all = new JsonObject();
			jobs.forEach((k, j) -> all.addProperty(k, j.future == null ? "error" : j.future.isDone() ? "done" : "running"));
			return new Result(all, Collections.emptyList());
		}
		Job job = jobs.get(jobId);
		if (job == null) throw new IllegalArgumentException("No run with id " + jobId);
		return await(job, waitSeconds);
	}

	private Result await(Job job, double waitSeconds) {
		long deadline = System.currentTimeMillis() + (long) (Math.min(waitSeconds, 1e9) * 1000);
		JsonObject json = new JsonObject();
		json.addProperty("job", job.id);
		List<BufferedImage> images = new ArrayList<>();
		if (job.future != null && job.failure == null) {
			Window seen = null;
			long seenSince = 0;
			while (!job.future.isDone()) {
				Window dialog = newModalDialog(job.dialogsBefore);
				if (dialog != seen) { seen = dialog; seenSince = System.currentTimeMillis(); }
				if (dialog != null && System.currentTimeMillis() - seenSince > 3000) { // progress dialogs come and go
					json.addProperty("status", "blocked_by_dialog");
					json.addProperty("dialog", ((Dialog) dialog).getTitle());
					json.addProperty("hint", "An input was not given to a command the module runs: answer the dialog, or pass that input");
					return new Result(json, images);
				}
				if (System.currentTimeMillis() > deadline) {
					json.addProperty("status", "running");
					json.addProperty("seconds", (System.currentTimeMillis() - job.started) / 1000);
					return new Result(json, images);
				}
				try { job.future.get(200, TimeUnit.MILLISECONDS); }
				catch (TimeoutException e) { /* keep waiting */ }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
				catch (ExecutionException e) { job.failure = e.getCause(); }
			}
		}
		logService.removeLogListener(job);
		Module module = null;
		if (job.future != null && job.failure == null) {
			try { module = job.future.get(); }
			catch (Throwable t) { job.failure = t instanceof ExecutionException ? t.getCause() : t; }
		}
		String scriptErrors = job.scriptErrors.toString().trim();
		if (job.failure != null || !scriptErrors.isEmpty()) {
			json.addProperty("status", "error");
			json.addProperty("error", job.failure != null ? causes(job.failure) : firstLines(scriptErrors, 12));
		}
		else if (module instanceof Cancelable && ((Cancelable) module).isCanceled()) {
			json.addProperty("status", "canceled");
			String reason = ((Cancelable) module).getCancelReason();
			if (reason != null) json.addProperty("reason", reason);
		}
		else {
			json.addProperty("status", "ok");
			if (module != null && !job.recorded) { steps.add(new Step(job.info, module)); job.recorded = true; }
		}
		if (!job.bound.isEmpty()) json.add("bound", GSON.toJsonTree(job.bound));
		if (module != null) json.add("outputs", outputs(module, images));
		if (job.objectsBefore != null) {
			Map<String, List<String>> created = new LinkedHashMap<>();
			for (Object o : objectService.getObjects(Object.class)) {
				if (job.objectsBefore.contains(o) || o instanceof Job) continue;
				created.computeIfAbsent(o.getClass().getSimpleName(), k -> new ArrayList<>()).add(nameOf(o));
			}
			if (!created.isEmpty()) {
				JsonObject byType = new JsonObject();
				created.forEach((type, names) -> byType.add(type, names.size() <= 5 ? GSON.toJsonTree(names) : new JsonPrimitive(names.size() + " objects")));
				json.add("new_objects", byType);
			}
		}
		if (!job.messages.isEmpty()) json.add("messages", GSON.toJsonTree(new ArrayList<>(job.messages)));
		if (job.started > 0) json.addProperty("seconds", (System.currentTimeMillis() - job.started) / 1000.0);
		return new Result(json, images);
	}

	// -- inputs --

	private Map<String, Object> bindInputs(Module module, JsonObject given, Job job) {
		ModuleInfo info = module.getInfo();
		Map<String, Object> inputs = new LinkedHashMap<>();
		Set<String> known = new LinkedHashSet<>();
		for (ModuleItem<?> item : info.inputs()) if (!isService(item.getType())) known.add(item.getName());
		for (String name : given.keySet()) {
			if (!known.contains(name)) throw new IllegalArgumentException("Unknown input '" + name + "'. Inputs: " + known);
			ModuleItem<?> item = info.getInput(name);
			Object value = toJava(given.get(name), item.getType(), name);
			List<?> choices = item.getChoices();
			if (value != null && choices != null && !choices.isEmpty() && !choices.contains(value)) {
				throw new IllegalArgumentException("Input '" + name + "': " + value + " is not one of " + choices);
			}
			inputs.put(name, value);
		}
		List<String> missing = new ArrayList<>();
		for (ModuleItem<?> item : info.inputs()) {
			String name = item.getName();
			if (inputs.containsKey(name) || isService(item.getType())) continue;
			if (item.getVisibility() == ItemVisibility.MESSAGE) { module.resolveInput(name); continue; }
			Object current = module.getInput(name);
			if (current == null && item.getDefaultValue() != null) current = item.getDefaultValue();
			if (current == null && item.getChoices() != null && !item.getChoices().isEmpty()) current = item.getChoices().get(0); // as a dialog would show
			if (current == null && !isSimple(item.getType())) {
				List<?> live = liveObjects(item.getType());
				if (live.size() == 1) { current = live.get(0); job.bound.put(name, nameOf(current)); }
			}
			if (current == null && item.isRequired() && !item.getType().isPrimitive() && item.getType() != String.class) {
				missing.add(name + " (" + item.getType().getSimpleName() + ")");
				continue;
			}
			if (current != null) module.setInput(name, current);
			module.resolveInput(name);
		}
		if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required input(s): " + String.join(", ", missing));
		return inputs;
	}

	private Object toJava(JsonElement value, Class<?> type, String name) {
		if (value == null || value.isJsonNull()) return null;
		if (type.isArray() && value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			Object result = Array.newInstance(type.getComponentType(), array.size());
			for (int i = 0; i < array.size(); i++) Array.set(result, i, toJava(array.get(i), type.getComponentType(), name + "[" + i + "]"));
			return result;
		}
		Object raw = value.isJsonPrimitive() ? primitive(value.getAsJsonPrimitive()) : value.toString();
		if (type.isInstance(raw)) return raw;
		if (raw instanceof String && !isSimple(type)) {
			Object named = byName((String) raw, type);
			if (named != null) return named;
		}
		Object converted = convertService.convert(raw, type);
		if (converted != null) return converted;
		if (type.isArray() && raw instanceof String) { // a single name for an array input
			Object one = toJava(value, type.getComponentType(), name);
			Object result = Array.newInstance(type.getComponentType(), 1);
			Array.set(result, 0, one);
			return result;
		}
		throw new IllegalArgumentException("Input '" + name + "': cannot make a " + type.getSimpleName() + " of " + value +
			(isSimple(type) ? "" : ". Live objects of that type: " + names(liveObjects(type))));
	}

	private static Object primitive(JsonPrimitive p) {
		if (p.isBoolean()) return p.getAsBoolean();
		if (p.isNumber()) {
			double d = p.getAsDouble();
			return d == Math.rint(d) && Math.abs(d) < Long.MAX_VALUE && !p.getAsString().contains(".") ? (Object) p.getAsLong() : d;
		}
		return p.getAsString();
	}

	// -- outputs --

	private JsonObject outputs(Module module, List<BufferedImage> images) {
		JsonObject out = new JsonObject();
		for (ModuleItem<?> item : module.getInfo().outputs()) {
			Object v = module.getOutput(item.getName());
			if (v == null && "result".equals(item.getName()) && module instanceof ScriptModule) continue;
			out.add(item.getName(), v == null ? com.google.gson.JsonNull.INSTANCE : toJson(v, images)); // a null output is a signal
		}
		return out;
	}

	private JsonElement toJson(Object v, List<BufferedImage> images) {
		if (v == null) return null;
		if (v instanceof Number) return new JsonPrimitive((Number) v);
		if (v instanceof Boolean) return new JsonPrimitive((Boolean) v);
		if (v instanceof CharSequence || v instanceof Enum || v instanceof Character) return new JsonPrimitive(v.toString());
		if (v instanceof File) {
			File f = (File) v;
			if (f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g)") && f.isFile()) {
				try { images.add(fit(javax.imageio.ImageIO.read(f))); } catch (Exception e) { /* path is enough */ }
			}
			return new JsonPrimitive(f.getAbsolutePath());
		}
		if (v instanceof BufferedImage) { images.add(fit((BufferedImage) v)); return new JsonPrimitive("image " + images.size()); }
		if (v.getClass().isArray()) {
			JsonArray a = new JsonArray();
			for (int i = 0; i < Array.getLength(v); i++) a.add(toJson(Array.get(v, i), images));
			return a;
		}
		BufferedImage rendered = renderIfImage(v);
		if (rendered != null) { // a picture goes to the agent, not to the object registry
			images.add(fit(rendered));
			return new JsonPrimitive("image " + images.size() + " (" + rendered.getWidth() + "x" + rendered.getHeight() + ")");
		}
		if (!objectService.getObjects(Object.class).contains(v)) objectService.addObject(v);
		JsonObject ref = new JsonObject();
		ref.addProperty("object", nameOf(v));
		ref.addProperty("type", v.getClass().getSimpleName());
		return ref;
	}

	/** An ImagePlus (without depending on ImageJ 1.x) is shown to the agent as a picture. */
	private static BufferedImage renderIfImage(Object v) {
		if (!"ij.ImagePlus".equals(v.getClass().getName())) return null;
		try { return (BufferedImage) v.getClass().getMethod("getBufferedImage").invoke(v); }
		catch (Exception e) { return null; }
	}

	private static BufferedImage fit(BufferedImage image) {
		if (image == null) return null;
		int w = image.getWidth(), h = image.getHeight();
		double s = Math.min(1.0, MAX_IMAGE_SIDE / (double) Math.max(w, h));
		if (s == 1.0 && image.getType() == BufferedImage.TYPE_INT_RGB) return image;
		BufferedImage out = new BufferedImage(Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(image, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		return out;
	}

	// -- helpers --

	/** A module by class name, identifier, menu path, title, or script file path. */
	public ModuleInfo resolve(String id) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("No module id");
		String key = id.trim();
		File file = new File(key);
		if (file.isFile()) {
			try { return context.getService(ScriptService.class).getScript(file); }
			catch (Exception e) { throw new IllegalArgumentException("Cannot read script " + key + ": " + e.getMessage()); }
		}
		if (key.startsWith("command:")) key = key.substring("command:".length());
		CommandInfo command = context.getService(CommandService.class).getCommand(key);
		if (command != null) return command;
		List<ModuleInfo> byTitle = new ArrayList<>();
		for (ModuleInfo info : moduleService.getModules()) {
			if (key.equals(info.getIdentifier()) || key.equals(info.getDelegateClassName())) return info;
			String menu = info.getMenuPath() == null ? "" : info.getMenuPath().toString();
			if (key.equals(menu) || key.equalsIgnoreCase(info.getTitle())) byTitle.add(info);
		}
		if (byTitle.size() == 1) return byTitle.get(0);
		if (byTitle.size() > 1) throw new IllegalArgumentException(byTitle.size() + " modules are called '" + key + "': use a class name");
		throw new IllegalArgumentException("No module '" + key + "': search for it first");
	}

	private String idOf(ModuleInfo info) {
		return info instanceof CommandInfo ? ((CommandInfo) info).getClassName() : info.getIdentifier();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private <P> List<P> processors(Class<?> type) {
		List<P> list = new ArrayList<>();
		List<?> all = context.getService(PluginService.class).createInstancesOfType((Class) type);
		for (Object p : all) if (!p.getClass().getName().matches(EXCLUDED_PROCESSORS)) list.add((P) p);
		return list;
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> liveObjects(Class<T> type) {
		List<T> list = new ArrayList<>();
		for (Object o : objectService.getObjects(Object.class)) if (type.isInstance(o) && !(o instanceof Job)) list.add((T) o);
		return list;
	}

	private Object byName(String name, Class<?> type) {
		for (Object o : liveObjects(type)) if (name.equals(nameOf(o)) || name.equals(String.valueOf(o))) return o;
		return null;
	}

	private String nameOf(Object o) {
		String n = objectService.getName(o);
		if (n != null) return n;
		for (String getter : new String[] { "getName", "getTitle" }) {
			try {
				Object v = o.getClass().getMethod(getter).invoke(o);
				if (v instanceof String && !((String) v).isEmpty()) return (String) v;
			}
			catch (Exception e) { /* next */ }
		}
		return String.valueOf(o);
	}

	private String names(List<?> objects) {
		List<String> n = new ArrayList<>();
		for (Object o : objects) n.add(nameOf(o));
		return n.toString();
	}

	private static boolean isService(Class<?> t) { return Service.class.isAssignableFrom(t) || Context.class.isAssignableFrom(t); }

	private static boolean isSimple(Class<?> t) {
		return t.isPrimitive() || Number.class.isAssignableFrom(t) || t == String.class || t == Boolean.class || t == Character.class ||
			t == File.class || t.isEnum() || (t.isArray() && isSimple(t.getComponentType()));
	}

	private static boolean isA(Class<?> c, String type) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			if (k.getSimpleName().equals(type) || k.getName().equals(type)) return true;
			for (Class<?> i : k.getInterfaces()) if (i.getSimpleName().equals(type) || i.getName().equals(type)) return true;
		}
		return false;
	}

	private static Set<Object> identitySet(List<Object> objects) {
		Set<Object> s = Collections.newSetFromMap(new IdentityHashMap<>());
		s.addAll(objects);
		return s;
	}

	/** The modal dialogs opened since a run started: a command waiting for an input it was not given. */
	private static Window newModalDialog(Set<Object> before) {
		for (Window w : Window.getWindows()) {
			if (w instanceof Dialog && w.isShowing() && ((Dialog) w).isModal() && (before == null || !before.contains(w))) return w;
		}
		return null;
	}

	/** Every distinct message down the cause chain: the useful one is rarely on top. */
	static String causes(Throwable t) {
		Set<String> lines = new LinkedHashSet<>();
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			String m = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
			if (!m.startsWith("ExecutionException") && !m.equals("RuntimeException: Module threw exception")) lines.add(m);
		}
		StackTraceElement[] where = rootOf(t).getStackTrace();
		if (where.length > 0) lines.add("at " + where[0]);
		return String.join("\n", lines);
	}

	private static Throwable rootOf(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
	}

	private static String firstLines(String s, int n) {
		String[] lines = s.split("\\R");
		return String.join("\n", java.util.Arrays.copyOf(lines, Math.min(n, lines.length)));
	}

	private static String firstSentence(String s) {
		String plain = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		int dot = plain.indexOf(". ");
		String first = dot > 0 ? plain.substring(0, dot + 1) : plain;
		return first.length() > 200 ? first.substring(0, 200) + "..." : first;
	}

	// -- record --

	/**
	 * The successful runs so far, as a Groovy script that replays them: every input a module ran with,
	 * defaults included, and object inputs referring to the step that produced them, else looked up by
	 * name. The agent's exploration becomes a pipeline to edit, parameterize and ship.
	 */
	public String script() {
		StringBuilder s = new StringBuilder();
		s.append("// Recorded from an agent session: ").append(steps.size()).append(" successful runs, in order.\n");
		s.append("// Every input is explicit, defaults included. Turn the values you want to vary into #@ parameters.\n");
		s.append("#@ CommandService command\n#@ ModuleService modules\n#@ ScriptService scripts\n#@ ObjectService objects\n\n");
		s.append("// modules.run(command.getCommand(...)): command.run returns null for dynamic commands (a SciJava design flaw)\n");
		s.append("def object = { Class type, String name -> objects.getObjects(type).find { objects.getName(it) == name || String.valueOf(it) == name } }\n\n");
		Map<Object, String> producedBy = new IdentityHashMap<>();
		for (int i = 0; i < steps.size(); i++) {
			Step step = steps.get(i);
			String var = "s" + (i + 1);
			List<String> args = new ArrayList<>();
			for (Map.Entry<String, Object> in : step.inputs.entrySet()) {
				String literal = groovy(in.getValue(), producedBy);
				args.add(in.getKey() + ": " + (literal == null ? "null /* " + in.getValue().getClass().getSimpleName() + ": not recordable */" : literal));
			}
			String map = args.isEmpty() ? "[:]" : "[\n\t" + String.join(",\n\t", args) + "]";
			s.append("// ").append(step.info.getTitle()).append('\n');
			if (step.info instanceof CommandInfo) {
				s.append("def ").append(var).append(" = modules.run(command.getCommand('").append(((CommandInfo) step.info).getClassName()).append("'), true, ").append(map).append(").get()\n\n");
			}
			else {
				s.append("def ").append(var).append(" = scripts.run(new File(").append(quote(step.info.getIdentifier().replaceFirst("^script:", ""))).append("), true, ").append(map).append(").get()\n\n");
			}
			step.outputs.forEach((name, value) -> { if (value != null && !isSimple(value.getClass())) producedBy.putIfAbsent(value, var + ".getOutput('" + name + "')"); });
		}
		return s.toString();
	}

	private String groovy(Object v, Map<Object, String> producedBy) {
		if (v == null) return "null";
		if (v instanceof String || v instanceof Character || v instanceof Enum) return quote(v.toString());
		if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return v.toString();
		if (v instanceof Number) return v + (v instanceof Float ? "f" : "d");
		if (v instanceof File) return "new File(" + quote(((File) v).getPath().replace('\\', '/')) + ")";
		if (v.getClass().isArray()) {
			List<String> items = new ArrayList<>();
			for (int i = 0; i < Array.getLength(v); i++) {
				String item = groovy(Array.get(v, i), producedBy);
				if (item == null) return null;
				items.add(item);
			}
			return "[" + String.join(", ", items) + "] as " + v.getClass().getComponentType().getName() + "[]";
		}
		if (producedBy.containsKey(v)) return producedBy.get(v);
		if (objectService.getObjects(Object.class).contains(v)) return "object(" + v.getClass().getName() + ", " + quote(nameOf(v)) + ")";
		return null;
	}

	private static String quote(String s) {
		return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
	}

	private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());

	/** A successful run: the module, and the values it ran with and produced. */
	private static class Step {
		final ModuleInfo info;
		final Map<String, Object> inputs = new LinkedHashMap<>();
		final Map<String, Object> outputs = new LinkedHashMap<>();

		Step(ModuleInfo info, Module module) {
			this.info = info;
			for (ModuleItem<?> item : info.inputs()) {
				if (isService(item.getType()) || item.getVisibility() == ItemVisibility.MESSAGE ||
					item.getType().getName().equals("org.scijava.widget.Button")) continue;
				inputs.put(item.getName(), module.getInput(item.getName()));
			}
			for (ModuleItem<?> item : info.outputs()) outputs.put(item.getName(), module.getOutput(item.getName()));
		}
	}

	/** One run: its future, and what it logged meanwhile. */
	private static class Job implements LogListener {
		ModuleInfo info;
		boolean recorded;
		String id;
		Future<Module> future;
		Throwable failure;
		long started;
		Set<Object> objectsBefore;
		Set<Object> dialogsBefore;
		final StringWriter scriptErrors = new StringWriter();
		final Map<String, String> bound = new LinkedHashMap<>();
		final Set<String> messages = Collections.synchronizedSet(new LinkedHashSet<>());

		@Override
		public void messageLogged(LogMessage message) {
			if (message.level() > LogLevel.WARN || messages.size() >= 20) return;
			String text = LogLevel.prefix(message.level()) + " " + message.text();
			if (message.throwable() != null) text += " <- " + causes(message.throwable()).replace('\n', ' ');
			messages.add(text.length() > 600 ? text.substring(0, 600) + "..." : text);
		}
	}

	@SuppressWarnings("unused")
	private static String stackTrace(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		return w.toString();
	}
}

/*-
 * #%L
 * Search, describe and run any SciJava module (command or script) from an agent, headless or live.
 * %%
 * Copyright (C) 2026 UNIGE
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */
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
import java.io.StringReader;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.scijava.Cancelable;
import org.scijava.Context;
import org.scijava.Initializable;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.event.EventHandler;
import org.scijava.event.EventService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogListener;
import org.scijava.log.LogMessage;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleRunner;
import org.scijava.module.ModuleService;
import org.scijava.module.event.ModuleCanceledEvent;
import org.scijava.module.event.ModuleErroredEvent;
import org.scijava.module.event.ModuleEvent;
import org.scijava.module.event.ModuleFinishedEvent;
import org.scijava.module.event.ModuleStartedEvent;
import org.scijava.module.process.ModulePostprocessor;
import org.scijava.module.process.ModulePreprocessor;
import org.scijava.module.process.PostprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.object.ObjectService;
import org.scijava.plugin.PluginService;
import org.scijava.plugin.SingletonPlugin;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.service.Service;
import org.scijava.thread.ThreadService;
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
 * warnings and errors the run logged: on its thread, on threads it started, or in tasks it gave
 * the ThreadService - not what the rest of Fiji logged meanwhile.
 */
public class ModuleGateway {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	/** Processors left out of agent runs: dialogs, remembered values, output windows. */
	private static final String EXCLUDED_PROCESSORS = "(?i).*(Harvester|LoadInputs|SaveInputs|Display).*";
	private static final int MAX_IMAGE_SIDE = 1024;
	/** MCP clients abort a request after about 60 s: a longer run is followed with {@link #status}. */
	public static final int MAX_WAIT_SECONDS = 40;
	/** Finished runs kept for {@link #status}; older ones are dropped, with their modules. */
	private static final int KEPT_JOBS = 20;

	private final Context context;
	private final ModuleService moduleService;
	private final ObjectService objectService;
	private final ConvertService convertService;
	private final LogService logService;
	private final ThreadService threadService;
	private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
	/** The job a thread runs for, inherited by the threads it starts. */
	private final InheritableThreadLocal<Job> jobMarker = new InheritableThreadLocal<>();
	private final Map<Thread, Job> jobThreads = new ConcurrentHashMap<>();

	public ModuleGateway(Context context) {
		this.context = context;
		this.moduleService = context.getService(ModuleService.class);
		this.objectService = context.getService(ObjectService.class);
		this.convertService = context.getService(ConvertService.class);
		this.logService = context.getService(LogService.class);
		this.threadService = context.getService(ThreadService.class);
		logService.addLogListener(message -> {
			Job job = currentJob();
			if (job != null) job.messageLogged(message);
		});
		context.getService(EventService.class).subscribe(this);
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

	/**
	 * The live objects of one type (simple or fully qualified class name), by name. Without a type, the number of
	 * objects per type: a session can hold thousands of objects (image sources, for instance).
	 */
	public String objects(String type) {
		boolean all = type == null || type.isEmpty();
		JsonArray list = new JsonArray();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Object o : objectService.getObjects(Object.class)) {
			if (o == null || o instanceof Job || isInfrastructure(o)) continue;
			if (all) { counts.merge(o.getClass().getSimpleName(), 1, Integer::sum); continue; }
			if (!isA(o.getClass(), type)) continue;
			JsonObject n = new JsonObject();
			n.addProperty("name", nameOf(o));
			n.addProperty("type", o.getClass().getSimpleName());
			list.add(n);
		}
		return GSON.toJson(all ? GSON.toJsonTree(counts) : list);
	}

	/**
	 * The ObjectService also holds the application's own parts: every singleton plugin (converters, formats, tools...)
	 * and the desktop file types. A plugin that is not a singleton, such as an atlas command, is the user's.
	 */
	private static boolean isInfrastructure(Object o) {
		return o instanceof SingletonPlugin || o.getClass().getName().equals("org.scijava.desktop.FileType");
	}

	// -- run --

	/**
	 * Runs a module with JSON inputs, and waits for it up to {@code waitSeconds}. A run still going
	 * after that - or waiting for a dialog - is reported with a job id, to follow with {@link #status}.
	 */
	public Result run(String id, String inputsJson, double waitSeconds) {
		return start(() -> resolve(id), inputsJson, waitSeconds);
	}

	/**
	 * Runs a script given as text, as {@link #run} runs a script file: its {@code #@} parameters are its inputs and
	 * outputs. Nothing is written to disk: {@link #script} replays it with its code.
	 */
	public Result runCode(String code, String language, String inputsJson, double waitSeconds) {
		return start(() -> codeInfo(code, language), inputsJson, waitSeconds);
	}

	private Result start(Supplier<ModuleInfo> resolver, String inputsJson, double waitSeconds) {
		Job job = new Job();
		try {
			ModuleInfo info = resolver.get();
			job.info = info;
			Module module = moduleService.createModule(info);
			job.module = module;
			if (module instanceof ScriptModule) ((ScriptModule) module).setErrorWriter(job.scriptErrors);
			bindInputs(module, inputsJson == null || inputsJson.isBlank() ? new JsonObject() : JsonParser.parseString(inputsJson).getAsJsonObject(), job);
			job.objectsBefore = identitySet(objectService.getObjects(Object.class));
			ModuleRunner runner = new ModuleRunner(context, module, this.<ModulePreprocessor>processors(PreprocessorPlugin.class), this.<ModulePostprocessor>processors(PostprocessorPlugin.class));
			job.started = System.currentTimeMillis();
			job.future = threadService.run(() -> runAs(job, runner));
		}
		catch (Throwable t) {
			job.failure = t;
		}
		job.id = UUID.randomUUID().toString().substring(0, 8);
		keep(job);
		return await(job, waitSeconds);
	}

	/** Runs a module on the current thread, marked as the job's: what it logs and the modules it runs belong to the job. */
	private Module runAs(Job job, ModuleRunner runner) {
		job.thread = Thread.currentThread();
		jobThreads.put(job.thread, job);
		jobMarker.set(job);
		try { return runner.call(); }
		finally {
			jobMarker.remove();
			jobThreads.remove(job.thread);
			job.ended = System.currentTimeMillis();
		}
	}

	/**
	 * The running job the current thread works for: its own thread, a thread it started, or a task it gave the
	 * ThreadService, such as a command it runs. A thread the job started keeps the marker after the job ended.
	 */
	private Job currentJob() {
		Job job = jobMarker.get();
		if (job != null && job.ended == 0) return job;
		Thread t = Thread.currentThread();
		for (int depth = 0; t != null && depth < 20; depth++, t = threadService.getParent(t)) {
			job = jobThreads.get(t);
			if (job != null) return job;
		}
		return null;
	}

	/** Keeps a job, and the last finished ones only: each holds its module, with its inputs and outputs. */
	private void keep(Job job) {
		synchronized (jobs) {
			jobs.put(job.id, job);
			List<String> finished = new ArrayList<>();
			jobs.forEach((id, j) -> { if (!j.running()) finished.add(id); });
			for (String id : finished.subList(0, Math.max(0, finished.size() - KEPT_JOBS))) jobs.remove(id);
		}
	}

	/** Follows a run started by {@link #run}; an empty id lists the runs, and the dialogs open. */
	public Result status(String jobId, double waitSeconds) {
		if (jobId == null || jobId.isEmpty()) {
			JsonObject all = new JsonObject();
			JsonArray dialogs = openDialogs();
			if (dialogs.size() > 0) all.add("dialogs", dialogs);
			JsonObject runs = new JsonObject();
			synchronized (jobs) {
				jobs.forEach((k, j) -> {
					JsonObject run = new JsonObject();
					run.addProperty("title", j.info == null ? null : j.info.getTitle());
					run.addProperty("status", j.running() ? "running" : j.outcome());
					run.addProperty("seconds", j.seconds());
					runs.add(k, run);
				});
			}
			all.add("jobs", runs);
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
		long blockedSince = 0;
		while (job.running()) {
			long now = System.currentTimeMillis();
			blockedSince = !waitsForDialog(job) ? 0 : blockedSince == 0 ? now : blockedSince;
			boolean blocked = blockedSince > 0 && now - blockedSince > 3000; // progress dialogs come and go
			if (blocked || now > deadline) {
				JsonArray dialogs = openDialogs();
				json.addProperty("status", blocked ? "blocked_by_dialog" : "running");
				json.addProperty("seconds", job.seconds());
				if (blocked && dialogs.size() > 0) {
					json.add("dialog", dialogs.get(dialogs.size() - 1)); // the newest
					json.addProperty("hint", "The job waits for this dialog: read it with fiji_ui_dialogs_read; answer only if the user asks");
				}
				if (dialogs.size() > 0) json.add("dialogs", dialogs);
				if (!job.nested.isEmpty()) json.add("nested", job.nestedRuns());
				return new Result(json, Collections.emptyList());
			}
			try { job.future.get(200, TimeUnit.MILLISECONDS); }
			catch (TimeoutException | ExecutionException | CancellationException e) { /* keep waiting, or read below */ }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); deadline = 0; }
		}
		List<BufferedImage> images = new ArrayList<>();
		String status = job.outcome();
		json.addProperty("status", status);
		String scriptErrors = job.scriptErrors.toString().trim();
		if (status.equals("error")) {
			json.addProperty("error", job.failure != null ? causes(job.failure) : !scriptErrors.isEmpty() ? firstLines(scriptErrors, 12) : errorOutput(job.module));
		}
		else if (status.equals("canceled")) {
			String reason = ((Cancelable) job.module).getCancelReason();
			if (reason != null) json.addProperty("reason", reason);
		}
		else {
			if (job.errors > 0) { // ended normally, yet something went wrong on the way
				json.addProperty("logged_errors", job.errors);
				json.addProperty("first_error", job.firstError);
			}
			if (!job.recorded && isRecorded(job.info)) { steps.add(new Step(job.info, job.module)); job.recorded = true; }
		}
		if (!job.bound.isEmpty()) json.add("bound", GSON.toJsonTree(job.bound));
		if (!job.nested.isEmpty()) json.add("nested", job.nestedRuns());
		if (job.module != null && job.failure == null) json.add("outputs", outputs(job.module, images));
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
		if (job.started > 0) json.addProperty("seconds", job.seconds());
		JsonArray dialogs = openDialogs(); // an error shown in a modal dialog blocks the window until it is closed
		if (dialogs.size() > 0) {
			json.add("dialogs", dialogs);
			json.addProperty("hint", "Modal dialogs are open: read them (fiji_ui_dialogs_read); one the run showed blocks the window until closed");
		}
		return new Result(json, images);
	}

	/** A module may report its failure in outputs rather than throw: success = false, the reason in error (biop-tools). */
	static boolean reportsFailure(Module module) {
		return module != null && module.getInfo().getOutput("success") != null && Boolean.FALSE.equals(module.getOutput("success"));
	}

	private static String errorOutput(Module module) {
		Object error = module.getInfo().getOutput("error") == null ? null : module.getOutput("error");
		return error == null || error.toString().isBlank() ? "The module reported success = false" : error.toString();
	}

	// -- nested runs: the modules a job runs in turn, such as a command calling another one --

	@EventHandler
	private void onEvent(ModuleStartedEvent e) {
		Job job = currentJob();
		if (job != null && e.getModule() != job.module && job.nested.size() < 20) job.nested.put(e.getModule(), "running");
	}

	@EventHandler
	private void onEvent(ModuleFinishedEvent e) { nestedEnded(e, reportsFailure(e.getModule()) ? "error" : "ok"); }

	@EventHandler
	private void onEvent(ModuleCanceledEvent e) { nestedEnded(e, "canceled"); }

	@EventHandler
	private void onEvent(ModuleErroredEvent e) { nestedEnded(e, "error"); }

	private void nestedEnded(ModuleEvent e, String status) {
		Job job = currentJob();
		if (job != null) job.nested.replace(e.getModule(), status);
	}

	// -- inputs --

	private void bindInputs(Module module, JsonObject given, Job job) {
		ModuleInfo info = module.getInfo();
		Set<String> known = new LinkedHashSet<>();
		for (ModuleItem<?> item : info.inputs()) if (!isService(item.getType())) known.add(item.getName());
		for (String name : given.keySet()) {
			if (!known.contains(name)) throw new IllegalArgumentException("Unknown input '" + name + "'. Inputs: " + known
				+ (module instanceof ScriptModule ? ". A #@ line whose class is not found is dropped: give the full class name (#@ lines ignore imports)" : ""));
			ModuleItem<?> item = info.getInput(name);
			if (item.getType().getName().equals("org.scijava.widget.Button")) {
				throw new IllegalArgumentException("'" + name + "' is a button of the module's dialog, which only fills other inputs: "
					+ "leave it out and give those inputs");
			}
			Object value = toJava(given.get(name), item.getType(), name);
			List<?> choices = item.getChoices();
			if (value != null && choices != null && !choices.isEmpty() && !choices.contains(value)) {
				throw new IllegalArgumentException("Input '" + name + "': " + value + " is not one of " + choices);
			}
			module.setInput(name, value);
			module.resolveInput(name);
		}
		List<String> missing = new ArrayList<>();
		for (ModuleItem<?> item : info.inputs()) {
			String name = item.getName();
			if (given.has(name) || isService(item.getType())) continue;
			if (item.getVisibility() == ItemVisibility.MESSAGE) { module.resolveInput(name); continue; }
			Object current = module.getInput(name);
			if (current == null && item.getDefaultValue() != null) current = item.getDefaultValue();
			if (current == null && item.getChoices() != null && !item.getChoices().isEmpty()) current = item.getChoices().get(0); // as a dialog would show
			if (current == null && !isSimple(item.getType())) {
				List<?> live = liveObjects(item.getType());
				if (live.size() == 1) { current = live.get(0); job.bound.put(name, nameOf(current)); }
			}
			if (current == null && item.isRequired() && !item.getType().isPrimitive() && (item.getType() != String.class || !mayBeInitialized(module, item))) {
				missing.add(name + " (" + item.getType().getSimpleName() + ")");
				continue;
			}
			if (current != null) module.setInput(name, current);
			module.resolveInput(name);
		}
		if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required input(s): " + String.join(", ", missing));
	}

	/** An initializer - the input's or the module's - may give a String input its value when the module runs. */
	private static boolean mayBeInitialized(Module module, ModuleItem<?> item) {
		return !isBlank(item.getInitializer()) || !isBlank(module.getInfo().getInitializer()) || module.getDelegateObject() instanceof Initializable;
	}

	private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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
		// The Window menu lists the open windows, a command's output window included, by their title
		if (byTitle.size() > 1) byTitle.removeIf(info -> "net.imagej.display.SelectWindow".equals(info.getDelegateClassName()));
		if (byTitle.size() == 1) return byTitle.get(0);
		if (byTitle.size() > 1) throw new IllegalArgumentException(byTitle.size() + " modules are called '" + key + "': use a class name");
		throw new IllegalArgumentException("No module '" + key + "': search for it first");
	}

	/** The code of the scripts given as text, for {@link #script}. */
	private final Map<ModuleInfo, String> codes = Collections.synchronizedMap(new IdentityHashMap<>());
	private final AtomicInteger codeRuns = new AtomicInteger();

	/** A script given as text, named code_N.<extension of its language>: the name sets the language. */
	private ModuleInfo codeInfo(String code, String language) {
		if (code == null || code.isBlank()) throw new IllegalArgumentException("No code");
		ScriptService scripts = context.getService(ScriptService.class);
		String name = language == null || language.isBlank() ? "groovy" : language.trim().replaceFirst("^\\.", "");
		ScriptLanguage lang = scripts.getLanguageByName(name);
		if (lang == null) lang = scripts.getLanguageByExtension(name);
		if (lang == null) {
			List<String> known = new ArrayList<>();
			for (ScriptLanguage l : scripts.getLanguages()) known.add(l.getLanguageName() + " (" + String.join(", ", l.getExtensions()) + ")");
			throw new IllegalArgumentException("No script language '" + name + "': " + String.join("; ", known));
		}
		ScriptInfo info = new ScriptInfo(context, "code_" + codeRuns.incrementAndGet() + "." + lang.getExtensions().get(0), new StringReader(code));
		codes.put(info, code);
		return info;
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
		// Unnamed, the ObjectService gives toString(): class@hash for most objects, while they may have a name of their own
		if (n != null && !n.equals(String.valueOf(o))) return n;
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

	/** The titles of the modal dialogs on screen, whoever opened them, oldest first. */
	private static JsonArray openDialogs() {
		JsonArray titles = new JsonArray();
		for (Window w : Window.getWindows()) {
			if (w instanceof Dialog && w.isShowing() && ((Dialog) w).isModal()) titles.add(Objects.toString(((Dialog) w).getTitle(), ""));
		}
		return titles;
	}

	/** Whether the job's thread waits for a modal dialog: one it shows, or one shown by the code it waits for on the event dispatch thread. */
	private static boolean waitsForDialog(Job job) {
		if (job.thread == null) return false;
		for (StackTraceElement f : job.thread.getStackTrace()) {
			boolean shows = f.getClassName().equals("java.awt.Dialog") && f.getMethodName().equals("show");
			if (shows || f.getClassName().equals("java.awt.EventQueue") && f.getMethodName().equals("invokeAndWait")) return openDialogs().size() > 0;
		}
		return false;
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

	/** The first n lines of a stack trace, and each 'Caused by' with the line below it: the useful one is rarely on top. */
	private static String firstLines(String s, int n) {
		String[] lines = s.split("\\R");
		List<String> kept = new ArrayList<>(java.util.Arrays.asList(lines).subList(0, Math.min(n, lines.length)));
		for (int i = n; i < lines.length; i++) {
			if (lines[i].startsWith("Caused by")) {
				kept.add(lines[i]);
				if (i + 1 < lines.length) kept.add(lines[i + 1]);
			}
		}
		return String.join("\n", kept);
	}

	private static String firstSentence(String s) {
		String plain = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		int dot = plain.indexOf(". ");
		String first = dot > 0 ? plain.substring(0, dot + 1) : plain;
		return first.length() > 200 ? first.substring(0, 200) + "..." : first;
	}

	// -- record --

	/**
	 * A module that only looks (a picture, a state) is left out of the recording by declaring the attribute
	 * record=false: {@code #@script(record="false")} in a script, {@code @Attr(name = "record", value = "false")} on a
	 * command.
	 */
	static boolean isRecorded(ModuleInfo info) {
		return !"false".equalsIgnoreCase(info.get("record"));
	}

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
			else if (codes.containsKey(step.info)) { // a script given as text: replayed with its code
				String name = step.info.getIdentifier().replaceFirst("^script:", "");
				s.append("def ").append(var).append(" = scripts.run(").append(quote(name)).append(", '''")
					.append(codes.get(step.info).replace("\\", "\\\\").replace("'''", "\\'\\'\\'"))
					.append("''', true, ").append(map).append(").get()\n\n");
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

	/** One run: its future, its thread, and what it logged and ran meanwhile. */
	private static class Job implements LogListener {
		ModuleInfo info;
		Module module;
		boolean recorded;
		String id;
		Future<Module> future;
		volatile Thread thread;
		Throwable failure;
		long started;
		volatile long ended;
		Set<Object> objectsBefore;
		final StringWriter scriptErrors = new StringWriter();
		final Map<String, String> bound = new LinkedHashMap<>();
		final Set<String> messages = Collections.synchronizedSet(new LinkedHashSet<>());
		int errors;
		String firstError;
		/** The modules the job ran in turn, with how each ended. */
		final Map<Module, String> nested = Collections.synchronizedMap(new LinkedHashMap<>());

		boolean running() { return future != null && !future.isDone(); }

		double seconds() {
			return started == 0 ? 0 : Math.round(((ended > 0 ? ended : System.currentTimeMillis()) - started) / 100.0) / 10.0;
		}

		/** What a finished run came to: error (it threw, or reported success = false), canceled, or ok. */
		String outcome() {
			if (future != null && failure == null) {
				try { future.get(); }
				catch (ExecutionException e) { failure = e.getCause(); }
				catch (Throwable t) { failure = t; }
			}
			if (failure != null || !scriptErrors.toString().isBlank()) return "error";
			if (module instanceof Cancelable && ((Cancelable) module).isCanceled()) return "canceled";
			return reportsFailure(module) ? "error" : "ok";
		}

		JsonArray nestedRuns() {
			JsonArray runs = new JsonArray();
			synchronized (nested) {
				nested.forEach((m, status) -> {
					JsonObject run = new JsonObject();
					run.addProperty("title", m.getInfo().getTitle());
					run.addProperty("status", status);
					runs.add(run);
				});
			}
			return runs;
		}

		@Override
		public synchronized void messageLogged(LogMessage message) {
			if (message.level() > LogLevel.WARN) return;
			String text = LogLevel.prefix(message.level()) + " " + message.text();
			if (message.throwable() != null) text += " <- " + causes(message.throwable()).replace('\n', ' ');
			text = text.length() > 600 ? text.substring(0, 600) + "..." : text;
			if (message.level() == LogLevel.ERROR && errors++ == 0) firstError = text;
			if (messages.size() < 20) messages.add(text);
		}
	}

	@SuppressWarnings("unused")
	private static String stackTrace(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		return w.toString();
	}
}

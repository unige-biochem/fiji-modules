package sc.fiji.llm.module;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.scijava.plugin.AbstractRichPlugin;
import org.scijava.plugin.Plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import sc.fiji.llm.tools.AiToolPlugin;

/**
 * Exposes {@link ModuleGateway} as five MCP tools. Every SciJava command and script becomes
 * reachable, so a plugin needs no tool of its own: its commands, with their descriptions, are its
 * tools.
 */
@Plugin(type = AiToolPlugin.class)
public class ModuleToolPlugin extends AbstractRichPlugin implements AiToolPlugin {

	private ModuleGateway gateway;
	private Map<ToolSpecification, ToolExecutor> tools;

	@Override
	public String getName() { return "SciJava Module Tools"; }

	@Override
	public synchronized Map<ToolSpecification, ToolExecutor> getTools() {
		if (tools != null) return tools;
		tools = new LinkedHashMap<>();
		add("fiji_module_search",
			"Find SciJava commands and scripts whose title, menu path, description or class contain all the words of the query. " +
				"Every Fiji command and plugin is a module: search here before writing code.",
			a -> text(gateway().search(a.get("query").getAsString(), 30)), "query");
		add("fiji_module_describe",
			"Describe a module's inputs and outputs (types, labels, descriptions, defaults and choices after its initializers ran), " +
				"the live objects that can fill its object inputs, and the artifact that provides it (provided_by: " +
					"groupId:artifactId:version). id: a class name, menu path, title, or script file path.",
			a -> text(gateway().describe(a.get("id").getAsString())), "id");
		add("fiji_module_run",
			"Run a module (command class, menu path, title, or the path of a script file) with inputs as a JSON object, e.g. " +
				"{\"sigma\": 2, \"image\": \"blobs.gif\"}. Never opens the module's dialog: inputs not given keep their default; an " +
				"object input with one live instance is bound to it, else pass its name (see fiji_objects). Returns status, outputs " +
				"(objects by name, pictures as images), new objects, and the warnings and errors logged meanwhile. Waits up to " +
				"wait_seconds (default 60); a longer run returns a job id for fiji_module_status.",
			a -> result(gateway().run(a.get("id").getAsString(), optional(a, "inputs", "{}"), Double.parseDouble(optional(a, "wait_seconds", "60")))),
			"id", "inputs", "wait_seconds");
		add("fiji_module_status",
			"Follow a run started by fiji_module_run: waits up to wait_seconds (default 60) and returns its result, or 'running'. " +
				"An empty job lists the runs.",
			a -> result(gateway().status(optional(a, "job", ""), Double.parseDouble(optional(a, "wait_seconds", "60")))),
			"job", "wait_seconds");
		add("fiji_objects",
			"List the live objects Fiji holds (images, viewers, sessions...), optionally of one type, by the names module inputs accept.",
			a -> text(gateway().objects(optional(a, "type", ""))), "?type");
		add("fiji_module_script",
			"The successful fiji_module_run calls of this session as a Groovy script that replays them, every input explicit. " +
				"Once a result is right, save it (path) to keep the pipeline: edit it, turn values into #@ parameters, run it " +
				"with fiji_module_run (its path is its id), or headless in a fresh Fiji.",
			a -> {
				String script = gateway().script();
				String path = optional(a, "path", "");
				if (path.isEmpty()) return text(script);
				try { java.nio.file.Files.write(java.nio.file.Paths.get(path), script.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
				catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot write " + path + ": " + e.getMessage()); }
				return text("{\"written\": " + new com.google.gson.Gson().toJson(path) + ", \"lines\": " + script.split("\n").length + "}");
			}, "?path");
		return tools;
	}

	private ModuleGateway gateway() {
		if (gateway == null) gateway = new ModuleGateway(getContext());
		return gateway;
	}

	/**
	 * fiji-llm's MCP bridge names each property after its description, so both are the name.
	 * The first parameter is required, unless its name starts with '?'.
	 */
	private void add(String name, String description, Function<JsonObject, ToolExecutionResult> body, String... params) {
		JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
		for (String p : params) schema.addStringProperty(p.replace("?", ""), p.replace("?", ""));
		if (!params[0].startsWith("?")) schema.required(params[0]);
		ToolSpecification spec = ToolSpecification.builder().name(name).description(description).parameters(schema.build()).build();
		ToolExecutor executor = new ToolExecutor() {
			@Override
			public String execute(ToolExecutionRequest request, Object memoryId) {
				return executeWithContext(request, null).resultText();
			}

			@Override
			public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext ctx) {
				try {
					String args = request.arguments();
					return body.apply(args == null || args.isBlank() ? new JsonObject() : JsonParser.parseString(args).getAsJsonObject());
				}
				catch (Throwable t) {
					return ToolExecutionResult.builder().isError(true)
						.resultText(new com.google.gson.Gson().toJson(Map.of("status", "error", "error", ModuleGateway.causes(t)))).build();
				}
			}
		};
		tools.put(spec, executor);
	}

	private static String optional(JsonObject a, String key, String fallback) {
		return a.has(key) && !a.get(key).isJsonNull() && !a.get(key).getAsString().isBlank() ? a.get(key).getAsString() : fallback;
	}

	private static ToolExecutionResult text(String s) {
		return ToolExecutionResult.builder().resultText(s).build();
	}

	private static ToolExecutionResult result(ModuleGateway.Result r) {
		List<Content> contents = new ArrayList<>();
		contents.add(TextContent.from(r.text()));
		for (BufferedImage image : r.images) {
			try {
				ByteArrayOutputStream png = new ByteArrayOutputStream();
				ImageIO.write(image, "png", png);
				contents.add(ImageContent.from(Base64.getEncoder().encodeToString(png.toByteArray()), "image/png"));
			}
			catch (Exception e) { /* the text result stands */ }
		}
		return ToolExecutionResult.builder().isError(r.isError()).resultContents(contents).build();
	}
}

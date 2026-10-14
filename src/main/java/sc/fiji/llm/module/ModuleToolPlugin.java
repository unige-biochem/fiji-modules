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
 * Exposes {@link ModuleGateway} as seven MCP tools. Every SciJava command and script becomes
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
			a -> text(gateway().search(a.get("query").getAsString(), 30)),
			"query: words that must all appear, e.g. 'gaussian blur'");
		add("fiji_module_describe",
			"Describe a module's inputs and outputs (types, labels, descriptions, defaults and choices after its initializers ran), " +
				"the live objects that can fill its object inputs, and the artifact that provides it (provided_by: " +
					"groupId:artifactId:version). id: a class name, menu path, title, or script file path.",
			a -> text(gateway().describe(a.get("id").getAsString())),
			"id: command title, menu path, class name, or script file path");
		add("fiji_module_run",
			"Run a module (command class, menu path, title, or the path of a script file) with inputs as a JSON object, e.g. " +
				"{\"sigma\": 2, \"image\": \"blobs.gif\"}. Never opens the module's dialog: inputs not given keep their default; an " +
				"object input with one live instance is bound to it, else pass its name (see fiji_objects). Returns status, outputs " +
				"(objects by name, pictures as images), new objects, the modules it ran in turn (nested), and the warnings and " +
				"errors it logged (messages). status is error when the module threw or reported success = false (error says why), " +
				"canceled, or ok; ok with logged_errors (their number) and first_error means it ended normally but logged errors: " +
				"check its outputs. Waits up to wait_seconds (default 30, at most 40: clients abort requests after about 60 s; " +
				"follow a longer run with fiji_module_status). A run still going returns its job id, status running, and the " +
				"titles of the modal dialogs open (dialogs); blocked_by_dialog means the job itself waits for a dialog.",
			a -> result(gateway().run(a.get("id").getAsString(), optional(a, "inputs", "{}"), waitSeconds(a))),
			"id: command title, menu path, class name, or script file path",
			"inputs: JSON object of input values by name, e.g. {\"sigma\": 2}; inputs not given keep their default",
			"wait_seconds: " + WAIT);
		add("fiji_module_run_script",
			"Run a script given as text (Groovy by default) as fiji_module_run runs a script file: same inputs, result, job id " +
				"and status, nothing written to disk. For what no command does: search the commands first. Declare what it needs " +
				"with #@ lines, e.g. '#@ ImagePlus image' (an object input with one live instance is bound to it) or " +
				"'#@ CommandService command', and what it returns with '#@output String result'. Recorded in fiji_module_script " +
				"with its code; '#@script(record=\"false\")' leaves out a script that only looks.",
			a -> result(gateway().runCode(a.get("code").getAsString(), optional(a, "language", "groovy"), optional(a, "inputs", "{}"), waitSeconds(a))),
			"code: the script, with its #@ parameters",
			"?language: groovy (default, recommended), or another language name or file extension; 'python' is Jython 2.7, " +
				"not CPython: no numpy, no pip packages",
			"?inputs: JSON object of input values by name, as for fiji_module_run",
			"wait_seconds: " + WAIT);
		add("fiji_module_status",
			"Follow a run started by fiji_module_run: waits up to wait_seconds and returns its result as fiji_module_run does, " +
				"or status running (or blocked_by_dialog) with the dialogs open. An empty job lists the runs - title, status " +
				"(running, ok, error or canceled) and seconds each, the last 20 finished ones kept - and the modal dialogs open.",
			a -> result(gateway().status(optional(a, "job", ""), waitSeconds(a))),
			"?job: the job id returned by fiji_module_run; empty lists the runs",
			"wait_seconds: " + WAIT);
		add("fiji_objects",
			"The live objects Fiji holds (images, viewers, sessions...): without a type, the number of objects per type; with a " +
				"type, the objects of that type by the names module inputs accept.",
			a -> text(gateway().objects(optional(a, "type", ""))),
			"?type: simple or full class name, e.g. ImagePlus; empty counts the objects per type");
		add("fiji_module_script",
			"The successful fiji_module_run calls of this session as a Groovy script that replays them, every input explicit " +
				"(modules declaring record=false, which only look, are left out). " +
				"Once a result is right, save it (path) to keep the pipeline: edit it, turn values into #@ parameters, run it " +
				"with fiji_module_run (its path is its id), or headless in a fresh Fiji.",
			a -> {
				String script = gateway().script();
				String path = optional(a, "path", "");
				if (path.isEmpty()) return text(script);
				try { java.nio.file.Files.write(java.nio.file.Paths.get(path), script.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
				catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot write " + path + ": " + e.getMessage()); }
				return text("{\"written\": " + new com.google.gson.Gson().toJson(path) + ", \"lines\": " + script.split("\n").length + "}");
			}, "?path: file to write the script to; empty returns it");
		return tools;
	}

	private ModuleGateway gateway() {
		if (gateway == null) gateway = new ModuleGateway(getContext());
		return gateway;
	}

	/**
	 * Parameters are strings "name: description". The first one is required, unless its name starts with '?'.
	 */
	private void add(String name, String description, Function<JsonObject, ToolExecutionResult> body, String... params) {
		JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
		for (String p : params) {
			int colon = p.indexOf(':');
			schema.addStringProperty(p.substring(0, colon).replace("?", ""), p.substring(colon + 1).trim());
		}
		if (!params[0].startsWith("?")) schema.required(params[0].substring(0, params[0].indexOf(':')));
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

	private static final String WAIT = "seconds to wait for the result before returning the job id (default 30, at most " +
		ModuleGateway.MAX_WAIT_SECONDS + ": clients abort requests after about 60 s; follow a longer run with fiji_module_status)";

	private static double waitSeconds(JsonObject a) {
		return Math.min(Double.parseDouble(optional(a, "wait_seconds", "30")), ModuleGateway.MAX_WAIT_SECONDS);
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

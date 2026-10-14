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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.module.ModuleService;
import org.scijava.object.ObjectService;
import org.scijava.plugin.Parameter;
import org.scijava.script.ScriptService;

import com.google.gson.JsonObject;

public class ModuleGatewayTest {

	private static Context context;
	private static ModuleGateway gateway;

	@BeforeClass
	public static void setUp() {
		context = new Context(CommandService.class, ObjectService.class, ConvertService.class, ScriptService.class); // not fiji-llm's services
		ModuleService modules = context.getService(ModuleService.class);
		for (Class<?> c : new Class<?>[] { Strings.class, Fails.class, LogsError.class, Outer.class, Inner.class, Sleeps.class }) {
			modules.addModule(new CommandInfo(c.getName()));
		}
		gateway = new ModuleGateway(context);
	}

	@AfterClass
	public static void tearDown() {
		context.dispose();
	}

	public static class Strings implements Command {
		@Parameter String name;
		@Parameter String initialized = "x";
		@Parameter(required = false) String note;
		@Parameter(type = ItemIO.OUTPUT) String result;
		@Override public void run() { result = name + initialized + note; }
	}

	public static class Fails implements Command {
		@Parameter(type = ItemIO.OUTPUT) Boolean success;
		@Parameter(type = ItemIO.OUTPUT) String error;
		@Override public void run() { success = false; error = "No such file"; }
	}

	public static class LogsError implements Command {
		@Parameter LogService log;
		@Override public void run() {
			sleep(600); // while another thread logs
			log.error("inside the job");
		}
	}

	public static class Outer implements Command {
		@Parameter CommandService commands;
		@Override public void run() {
			try { commands.run(Inner.class, false).get(); }
			catch (Exception e) { throw new RuntimeException(e); }
		}
	}

	public static class Inner implements Command {
		@Parameter LogService log;
		@Override public void run() { log.error("from the nested command"); }
	}

	public static class Sleeps implements Command {
		@Override public void run() { sleep(1500); }
	}

	private static void sleep(long ms) {
		try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}

	@Test
	public void aRequiredStringIsMissingUnlessInitialized() {
		ModuleGateway.Result missing = gateway.run(Strings.class.getName(), "{}", 10);
		assertTrue(missing.isError());
		assertEquals("IllegalArgumentException: Missing required input(s): name (String)", missing.json.get("error").getAsString().split("\n")[0]);
		ModuleGateway.Result given = gateway.run(Strings.class.getName(), "{\"name\": \"a\"}", 10);
		assertEquals("ok", given.json.get("status").getAsString());
		assertEquals("axnull", given.json.getAsJsonObject("outputs").get("result").getAsString());
	}

	@Test
	public void successFalseIsAnError() {
		ModuleGateway.Result r = gateway.run(Fails.class.getName(), "{}", 10);
		assertEquals("error", r.json.get("status").getAsString());
		assertEquals("No such file", r.json.get("error").getAsString());
	}

	@Test
	public void onlyWhatTheJobLogsIsItsOwn() throws Exception {
		Thread elsewhere = new Thread(() -> { sleep(200); context.getService(LogService.class).error("elsewhere"); });
		elsewhere.start();
		ModuleGateway.Result r = gateway.run(LogsError.class.getName(), "{}", 10);
		elsewhere.join();
		assertEquals("ok", r.json.get("status").getAsString());
		assertEquals(1, r.json.get("logged_errors").getAsInt());
		assertTrue(r.json.get("first_error").getAsString().contains("inside the job"));
		assertFalse(r.json.get("messages").toString().contains("elsewhere"));
	}

	@Test
	public void nestedRunsAreReported() {
		ModuleGateway.Result r = gateway.run(Outer.class.getName(), "{}", 10);
		assertEquals("ok", r.json.get("status").getAsString());
		JsonObject nested = r.json.getAsJsonArray("nested").get(0).getAsJsonObject();
		assertEquals("ok", nested.get("status").getAsString());
		assertEquals(1, r.json.get("logged_errors").getAsInt()); // the nested command runs on another thread
	}

	@Test
	public void aLongRunIsFollowedAndListed() {
		ModuleGateway.Result r = gateway.run(Sleeps.class.getName(), "{}", 0.2);
		assertEquals("running", r.json.get("status").getAsString());
		String job = r.json.get("job").getAsString();
		JsonObject listed = gateway.status("", 0).json.getAsJsonObject("jobs").getAsJsonObject(job);
		assertEquals("running", listed.get("status").getAsString());
		assertEquals("ok", gateway.status(job, 10).json.get("status").getAsString());
		assertEquals("ok", gateway.status("", 0).json.getAsJsonObject("jobs").getAsJsonObject(job).get("status").getAsString());
	}

	@Test
	public void aScriptGivenAsTextRunsAndIsReplayedWithItsCode() {
		String code = "#@ Integer x\n#@output Integer y\ny = x * 2 // replayed between '''\n";
		ModuleGateway.Result r = gateway.runCode(code, "groovy", "{\"x\": 21}", 10);
		assertEquals("ok", r.json.get("status").getAsString());
		assertEquals(42, r.json.getAsJsonObject("outputs").get("y").getAsInt());
		String script = gateway.script();
		assertTrue(script.contains("scripts.run('code_"));
		assertTrue(script.contains("y = x * 2 // replayed between \\'\\'\\'")); // escaped in the ''' string of the replay
		assertTrue(gateway.runCode("x", "cobol", "{}", 10).isError());
	}

	@Test
	public void onlyTheLastFinishedRunsAreKept() {
		for (int i = 0; i < 25; i++) gateway.run(Fails.class.getName(), "{}", 10);
		JsonObject jobs = gateway.status("", 0).json.getAsJsonObject("jobs");
		assertEquals(21, jobs.size()); // the 20 finished when the last run started, and that run
		jobs.entrySet().forEach(e -> assertEquals("error", e.getValue().getAsJsonObject().get("status").getAsString()));
	}
}

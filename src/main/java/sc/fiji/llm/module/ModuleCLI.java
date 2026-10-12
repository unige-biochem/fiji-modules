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

import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import org.scijava.Context;

/**
 * One-shot run of a module in a fresh JVM, for a pipeline step:
 * <pre>jgo ... @sc.fiji.llm.module.ModuleCLI run &lt;id|script&gt; ['{json inputs}']</pre>
 * Prints ONE line {@code MODULE_RESULT {json}} on stdout (images written next to the working
 * directory), and exits 0 on success, 1 on error. Also: {@code search <words>}, {@code describe <id>}.
 */
public class ModuleCLI {

	public static void main(String... args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: ModuleCLI run <id|script> ['{json inputs}'] | search <words> | describe <id>");
			System.exit(2);
		}
		preinitLegacy();
		Context context = new Context();
		int code = 0;
		try {
			ModuleGateway gateway = new ModuleGateway(context);
			switch (args[0]) {
				case "search":
					System.out.println(gateway.search(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)), 50));
					break;
				case "describe":
					System.out.println(gateway.describe(args[1]));
					break;
				case "run":
					ModuleGateway.Result r = gateway.run(args[1], args.length > 2 ? args[2] : "{}", 1e9); // no timeout: a pipeline step runs to its end
					List<java.awt.image.BufferedImage> images = r.images;
					for (int i = 0; i < images.size(); i++) {
						File f = new File("module-image-" + (i + 1) + ".png");
						ImageIO.write(images.get(i), "png", f);
						r.json.addProperty("image_" + (i + 1), f.getAbsolutePath());
					}
					System.out.println("MODULE_RESULT " + r.json.toString());
					code = r.isError() ? 1 : 0;
					break;
				default:
					System.err.println("Unknown action " + args[0]);
					code = 2;
			}
		}
		finally {
			System.out.flush();
			context.dispose();
		}
		System.exit(code);
	}

	/** ImageJ 1.x classes must be patched before any of them loads; a no-op without imagej-legacy. */
	private static void preinitLegacy() {
		try { Class.forName("net.imagej.patcher.LegacyInjector").getMethod("preinit").invoke(null); }
		catch (Throwable t) { /* no ImageJ 1.x on the class path */ }
	}
}

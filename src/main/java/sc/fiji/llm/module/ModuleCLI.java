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

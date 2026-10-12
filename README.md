[![Build Status](https://github.com/unige-biochem/fiji-modules/actions/workflows/build.yml/badge.svg)](https://github.com/unige-biochem/fiji-modules/actions/workflows/build.yml)

# fiji-modules

Search, describe and run any SciJava module (a command or a `#@` script) on behalf of an agent, in a live
Fiji or as a one-shot pipeline step, and record a session as a replayable Groovy script.

- `ModuleGateway`: pure SciJava, no LLM dependency. Agent semantics: never opens a dialog, never reuses
  remembered input values; objects (sessions, images) by name through the `ObjectService`; errors with their
  causes and the warnings logged meanwhile.
- `ModuleToolPlugin`: six MCP tools on fiji-llm's server (`fiji_module_search`, `fiji_module_describe`,
  `fiji_module_run`, `fiji_module_status`, `fiji_objects`, `fiji_module_script`).
- `ModuleCLI`: one-shot run in a fresh JVM, for a jgo `module` entry point: prints one `MODULE_RESULT {json}`
  line, exits 0 or 1.

Experimental. The package is `sc.fiji.llm.module` so that it can move into fiji-llm
unchanged. Depends on fiji-llm (not on Maven yet) and on scijava-introspect.

Build: `mvn install` (needs fiji-llm `0.1.0-SNAPSHOT` installed locally).

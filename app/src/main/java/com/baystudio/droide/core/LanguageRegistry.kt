package com.baystudio.droide.core

data class RunPlan(
    val steps: List<List<String>>,
    val display: String,
    val preparation: RunPreparation? = null,
    /** Non-null only for built-in Python Run; absence is also approval-bound. */
    val pythonConfigurationIdentity: String? = null,
)

data class RunPreparation(
    val directory: String,
    val files: Map<String, String>,
    val mirrorTypeScriptPackageScopes: Boolean = false,
)

data class DeclarativeLanguageSpec(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val filenames: List<String>,
)

data class LanguageDef(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val runner: ((String) -> RunPlan)?,
    val template: String,
    val filenames: List<String> = emptyList(),
)

object LanguageRegistry {
    private fun one(display: String, vararg argv: String) = { path: String ->
        RunPlan(listOf(argv.map { if (it == "{FILE}") path else it }), display.replace("{FILE}", path))
    }

    private val builtIns = listOf(
        LanguageDef("python", "Python", listOf("py", "pyi"), { path ->
            one("python3 {FILE}", "python3", "{FILE}")(path).copy(pythonConfigurationIdentity = "absent")
        }, "print('Hello from Droide')\n"),
        LanguageDef("javascript", "JavaScript", listOf("js", "mjs", "cjs"), one("node {FILE}", "node", "{FILE}"), "console.log('Hello from Droide');\n"),
        LanguageDef("typescript", "TypeScript", listOf("ts", "mts", "cts"), SourceRunPreparation::typeScriptPlan, "console.log('Hello from Droide');\n"),
        LanguageDef("react", "React / TSX", listOf("jsx", "tsx"), null, "export default () => <div>Hello</div>\n"),
        LanguageDef("go", "Go", listOf("go"), one("go run {FILE}", "go", "run", "{FILE}"), "package main\nimport \"fmt\"\nfunc main(){ fmt.Println(\"Hello\") }\n"),
        LanguageDef("rust", "Rust", listOf("rs"), { path -> RunPlan(listOf(listOf("rustc", path, "-o", ".droide/run/rust"), listOf("./.droide/run/rust")), "rustc $path -o .droide/run/rust && ./.droide/run/rust") }, "fn main(){ println!(\"Hello\"); }\n"),
        LanguageDef("java", "Java", listOf("java"), one("java {FILE}", "java", "{FILE}"), "public class Main { public static void main(String[] a){ System.out.println(\"Hello\"); } }\n"),
        LanguageDef("kotlin", "Kotlin", listOf("kt", "kts"), { path ->
            if (path.substringAfterLast('.').equals("kts", ignoreCase = true)) {
                RunPlan(listOf(listOf("kotlinc", "-script", path)), "kotlinc -script $path")
            } else RunPlan(listOf(listOf("kotlinc", path, "-include-runtime", "-d", ".droide/run/kotlin.jar"), listOf("java", "-jar", ".droide/run/kotlin.jar")), "kotlinc $path -include-runtime -d .droide/run/kotlin.jar && java -jar .droide/run/kotlin.jar")
        }, "fun main(){ println(\"Hello\") }\n"),
        LanguageDef("c", "C", listOf("c", "h"), SourceRunPreparation::nativePlan, "#include <stdio.h>\nint main(){ printf(\"Hello\\n\"); return 0; }\n"),
        LanguageDef("cpp", "C++", listOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++"), SourceRunPreparation::nativePlan, "#include <iostream>\nint main(){ std::cout << \"Hello\"; }\n"),
        LanguageDef("csharp", "C#", listOf("cs"), SourceRunPreparation::cSharpPlan, "Console.WriteLine(\"Hello\");\n"),
        LanguageDef("php", "PHP", listOf("php"), one("php {FILE}", "php", "{FILE}"), "<?php echo \"Hello\\n\"; ?>\n"),
        LanguageDef("ruby", "Ruby", listOf("rb"), one("ruby {FILE}", "ruby", "{FILE}"), "puts 'Hello'\n"),
        LanguageDef("swift", "Swift", listOf("swift"), one("swift {FILE}", "swift", "{FILE}"), "print(\"Hello\")\n"),
        LanguageDef("dart", "Dart", listOf("dart"), one("dart {FILE}", "dart", "{FILE}"), "void main(){ print('Hello'); }\n"),
        LanguageDef("shell", "Shell", listOf("sh", "bash", "zsh"), { path ->
            val shell = when (path.substringAfterLast('.').lowercase()) { "bash" -> "bash"; "zsh" -> "zsh"; else -> "sh" }
            RunPlan(listOf(listOf(shell, path)), "$shell $path")
        }, "echo Hello\n"),
        LanguageDef("html", "HTML", listOf("html", "htm"), null, "<!DOCTYPE html><html><body>Hello</body></html>\n"),
        LanguageDef("css", "CSS", listOf("css"), null, "body { color: #58A6FF; }\n"),
        LanguageDef("scss", "SCSS", listOf("scss"), null, "$" + "accent: #58A6FF;\nbody { color: $" + "accent; }\n"),
        LanguageDef("less", "Less", listOf("less"), null, "@accent: #58A6FF;\nbody { color: @accent; }\n"),
        LanguageDef("json", "JSON", listOf("json"), null, "{\"hello\": \"world\"}\n"),
        LanguageDef("jsonc", "JSON with Comments", listOf("jsonc"), null, "{\n  // Configuration\n  \"hello\": \"world\",\n}\n"),
        LanguageDef("xml", "XML", listOf("xml", "xsd", "xsl", "xslt", "svg", "rng", "dtd"), null, "<?xml version=\"1.0\"?>\n<hello>world</hello>\n"),
        LanguageDef("yaml", "YAML", listOf("yaml", "yml"), null, "hello: world\n"),
        LanguageDef("markdown", "Markdown", listOf("md", "markdown", "mdx"), null, "# Hello\n"),
        LanguageDef("sql", "SQL", listOf("sql"), null, "SELECT 'Hello';\n"),
        LanguageDef("r", "R", listOf("r"), one("Rscript {FILE}", "Rscript", "{FILE}"), "print('Hello')\n"),
        LanguageDef("lua", "Lua", listOf("lua"), one("lua {FILE}", "lua", "{FILE}"), "print('Hello')\n"),
        LanguageDef("perl", "Perl", listOf("pl", "pm"), one("perl {FILE}", "perl", "{FILE}"), "print \"Hello\\n\";\n"),
        LanguageDef("haskell", "Haskell", listOf("hs", "lhs"), one("runhaskell {FILE}", "runhaskell", "{FILE}"), "main = putStrLn \"Hello\"\n"),
        LanguageDef("scala", "Scala", listOf("scala", "sc", "sbt"), one("scala {FILE}", "scala", "{FILE}"), "object Main extends App { println(\"Hello\") }\n"),
        LanguageDef("elixir", "Elixir", listOf("ex", "exs"), one("elixir {FILE}", "elixir", "{FILE}"), "IO.puts \"Hello\"\n"),
        LanguageDef("erlang", "Erlang", listOf("erl", "hrl"), one("escript {FILE}", "escript", "{FILE}"), "main(_)-> io:format(\"Hello~n\").\n"),
        LanguageDef("clojure", "Clojure", listOf("clj", "cljs", "cljc", "edn", "bb", "cljd", "clj_kondo"), one("clojure {FILE}", "clojure", "{FILE}"), "(println \"Hello\")\n"),
        LanguageDef("zig", "Zig", listOf("zig", "zon"), one("zig run {FILE}", "zig", "run", "{FILE}"), "const std = @import(\"std\"); pub fn main() void { std.debug.print(\"Hello\", .{}); }\n"),
        LanguageDef("nim", "Nim", listOf("nim"), one("nim c -r {FILE}", "nim", "c", "-r", "{FILE}"), "echo \"Hello\"\n"),
        LanguageDef("toml", "TOML", listOf("toml"), null, "[hello]\nworld = \"hi\"\n"),
        LanguageDef("dockerfile", "Dockerfile", listOf("dockerfile"), null, "FROM alpine\n"),
        LanguageDef("makefile", "Makefile", listOf("mk", "mak", "makefile"), null, "all:\n\techo Hello\n", filenames = listOf("Makefile", "makefile", "GNUmakefile")),
        LanguageDef("vue", "Vue", listOf("vue"), null, "<template>Hello</template>\n"),
        LanguageDef("svelte", "Svelte", listOf("svelte"), null, "<script>let x='Hello'</script>\n"),
        LanguageDef("graphql", "GraphQL", listOf("graphql", "gql"), null, "query { hello }\n"),
        LanguageDef("solidity", "Solidity", listOf("sol"), null, "pragma solidity ^0.8.0; contract Hello {}\n"),
        LanguageDef("objectivec", "Objective-C", listOf("m", "mm"), null, "#import <Foundation/Foundation.h>\nint main(){ NSLog(@\"Hello\"); }\n"),
        LanguageDef("assembly", "Assembly (AArch64/GNU)", listOf("asm", "s"), SourceRunPreparation::nativePlan, ".text\n.global main\n.type main, %function\nmain:\n    mov w0, #0\n    ret\n"),
        LanguageDef("powershell", "PowerShell", listOf("ps1", "psm1", "psd1"), one("pwsh {FILE}", "pwsh", "{FILE}"), "Write-Host \"Hello\"\n"),
        LanguageDef("batch", "Batch", listOf("bat", "cmd"), null, "@echo Hello\n"),
        LanguageDef("vim", "Vim Script", listOf("vim"), null, "echo \"Hello\"\n", filenames = listOf(".vimrc", "_vimrc")),
        LanguageDef("latex", "LaTeX / BibTeX", listOf("tex", "sty", "cls", "lco", "bib", "bibtex"), null, "\\documentclass{article}\\begin{document}Hello\\end{document}\n"),
        LanguageDef("ini", "INI", listOf("ini", "cfg", "conf"), null, "[hello]\nworld=hi\n"),
        LanguageDef("properties", "Properties", listOf("properties"), null, "hello=world\n"),
        LanguageDef("prisma", "Prisma", listOf("prisma"), null, "datasource db { provider = \"sqlite\" }\n"),
        LanguageDef("terraform", "Terraform", listOf("tf", "tfvars"), null, "resource \"null\" \"hello\" {}\n"),
        LanguageDef("proto", "Protobuf", listOf("proto"), null, "syntax = \"proto3\";\n"),
        LanguageDef("cmake", "CMake", listOf("cmake"), null, "cmake_minimum_required(VERSION 3.22)\nproject(Hello LANGUAGES C CXX)\n", filenames = listOf("CMakeLists.txt")),
    )

    fun isBuiltIn(languageId: String): Boolean = builtIns.any { it.id == languageId }

    private val dynamicByExtension = linkedMapOf<String, List<LanguageDef>>()

    val all: List<LanguageDef>
        @Synchronized get() = (builtIns + dynamicByExtension.values.flatten()).distinctBy { it.id to it.extensions }

    @Synchronized
    fun registerDeclarative(extensionId: String, contributions: List<DeclarativeLanguageSpec>) {
        dynamicByExtension[extensionId] = contributions.map { contribution ->
            LanguageDef(
                id = contribution.id,
                name = contribution.name,
                extensions = contribution.extensions.map { it.removePrefix(".") },
                runner = null,
                template = "",
                filenames = contribution.filenames.toList(),
            )
        }
    }

    @Synchronized
    fun unregisterDeclarative(extensionId: String) { dynamicByExtension.remove(extensionId) }

    fun forExtension(ext: String): LanguageDef? = all.firstOrNull { ext.lowercase() in it.extensions.map(String::lowercase) }
    fun forFile(path: String): LanguageDef? {
        val leaf = path.replace('\\', '/').substringAfterLast('/').lowercase()
        all.firstOrNull { language -> language.filenames.any { it.equals(leaf, ignoreCase = true) } }?.let { return it }
        if (path.substringAfterLast('.', "") in setOf("C", "H")) return all.firstOrNull { it.id == "cpp" }
        if (leaf == "dockerfile") return all.firstOrNull { it.id == "dockerfile" }
        if (leaf == "makefile") return all.firstOrNull { it.id == "makefile" }
        return forExtension(path.substringAfterLast('.', "").lowercase())
    }
    fun runPlanFor(path: String): RunPlan? {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension == "zon" || extension == "hrl" || extension == "sbt") return null
        val language = forFile(path) ?: return null
        // Syntax metadata does not imply that Clojure CLI can run every dialect/data file.
        if (language.id == "powershell" && extension != "ps1") return null
        if (language.id == "clojure" && extension != "clj") return null
        return language.runner?.invoke(path)
    }
    fun templateFor(path: String): String =
        if (path.substringAfterLast('.', "").equals("lhs", ignoreCase = true) && forFile(path)?.id == "haskell")
            "> main = putStrLn \"Hello\"\n"
        else if (path.substringAfterLast('.', "").equals("sbt", ignoreCase = true)) ""
        else forFile(path)?.template.orEmpty()

    fun runCommandFor(path: String): String? = runPlanFor(path)?.display
}

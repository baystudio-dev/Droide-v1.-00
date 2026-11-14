package com.baystudio.droide.core

// UI surfaces should never implement their own extension switch.


object FileIconRegistry {
    private val fileNames = mapOf(
        "androidmanifest.xml" to "android",
        "build.gradle" to "gradle",
        "build.gradle.kts" to "gradle",
        "settings.gradle" to "gradle",
        "settings.gradle.kts" to "gradle",
        "gradle.properties" to "gradle",
        "gradlew" to "gradle",
        "gradlew.bat" to "gradle",
        "dockerfile" to "docker",
        "containerfile" to "docker",
        "makefile" to "makefile",
        "gnumakefile" to "makefile",
        "cmakelists.txt" to "cmake",
        "package.json" to "nodejs",
        "package-lock.json" to "lock",
        "pnpm-lock.yaml" to "lock",
        "yarn.lock" to "lock",
        "cargo.lock" to "lock",
        "poetry.lock" to "lock",
        "composer.lock" to "lock",
        ".gitignore" to "git",
        ".gitattributes" to "git",
        ".gitmodules" to "git",
        ".gitconfig" to "git",
        ".gitkeep" to "git",
        ".gitmessage" to "git",
        ".dockerignore" to "docker",
        "docker-compose.yml" to "docker",
        "docker-compose.yaml" to "docker",
        "compose.yml" to "docker",
        "compose.yaml" to "docker",
        "cmakecache.txt" to "cmake",
        "cmakepresets.json" to "cmake",
        "cmakeuserpresets.json" to "cmake",
        "cargo.toml" to "rust",
        "rust-toolchain.toml" to "rust",
        "go.mod" to "gomod",
        "go.sum" to "gomod",
        "go.work" to "gomod",
        "pyproject.toml" to "python",
        "requirements.txt" to "python",
        "pipfile" to "python",
        "pipfile.lock" to "lock",
        "uv.lock" to "lock",
        "gemfile" to "ruby",
        "gemfile.lock" to "lock",
        ".ruby-version" to "ruby",
        ".npmrc" to "npm",
        ".npmignore" to "npm",
        ".nvmrc" to "nodejs",
        ".node-version" to "nodejs",
        ".yarnrc" to "yarn",
        ".yarnrc.yml" to "yarn",
        ".eslintignore" to "eslint",
        ".prettierignore" to "prettier",
        ".bashrc" to "shell",
        ".bash_profile" to "shell",
        ".bash_logout" to "shell",
        ".profile" to "shell",
        ".zshrc" to "shell",
        ".zprofile" to "shell",
        ".envrc" to "shell",
        ".vimrc" to "vim",
        "_vimrc" to "vim",
        ".editorconfig" to "settings",
        ".env" to "env",
        ".env.defaults" to "env",
        ".env.example" to "env",
        ".env.sample" to "env",
        ".env.template" to "env",
        ".env.schema" to "env",
        ".env.local" to "env",
        ".env.dev" to "env",
        ".env.development" to "env",
        ".env.alpha" to "env",
        ".env.e2e" to "env",
        ".env.qa" to "env",
        ".env.dist" to "env",
        ".env.preprod" to "env",
        ".env.prod" to "env",
        ".env.production" to "env",
        ".env.prod.example" to "env",
        ".env.production.example" to "env",
        ".env.stg" to "env",
        ".env.stage" to "env",
        ".env.staging" to "env",
        ".env.preview" to "env",
        ".env.test" to "env",
        ".env.testing" to "env",
        ".env.dev.local" to "env",
        ".env.development.local" to "env",
        ".env.qa.local" to "env",
        ".env.prod.local" to "env",
        ".env.production.local" to "env",
        ".env.stg.local" to "env",
        ".env.staging.local" to "env",
        ".env.test.local" to "env",
        ".env.test.example" to "env",
        ".env.uat" to "env",
        "license" to "license",
        "license.md" to "license",
        "license.txt" to "license",
        "licence" to "license",
        "licence.md" to "license",
        "licence.txt" to "license",
        "copying" to "license",
        "notice" to "license",
        "readme" to "markdown",
        "readme.md" to "markdown",
    ) + buildMap {
        for (extension in listOf("js", "mjs", "cjs", "ts", "mts", "cts")) {
            for (tool in listOf("eslint", "prettier", "vite", "astro")) put("$tool.config.$extension", tool)
        }
        for (tool in listOf("eslint", "prettier")) {
            put(".${tool}rc", tool)
            for (extension in listOf("json", "json5", "yaml", "yml", "js", "cjs", "mjs", "ts", "cts", "mts")) {
                put(".${tool}rc.$extension", tool)
            }
        }
        put("tsconfig.json", "tsconfig")
        for (scope in listOf("app", "base", "build", "dev", "prod", "spec", "lib", "lib.prod", "node", "web",
            "test", "e2e", "worker", "server", "client", "paths", "eslint", "vitest", "cjs", "esm")) {
            put("tsconfig.$scope.json", "tsconfig")
        }
        for (profile in listOf("dev", "development", "prod", "production", "test", "local")) {
            put("dockerfile.$profile", "docker")
            put("containerfile.$profile", "docker")
        }
    }

    private val compoundSuffixes = listOf(
        ".gradle.kts" to "gradle",
        ".d.ts" to "typescript",
        ".d.mts" to "typescript",
        ".d.cts" to "typescript",
        ".test.tsx" to "react",
        ".spec.tsx" to "react",
        ".test.jsx" to "react",
        ".spec.jsx" to "react",
        ".stories.tsx" to "react",
        ".stories.jsx" to "react",
        ".dockerfile" to "docker",
        ".tar.gz" to "zip",
        ".tar.bz2" to "zip",
        ".tar.xz" to "zip",
    )

    private val extensions = mapOf(
        "py" to "python", "pyi" to "python", "pyw" to "python",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "jsx" to "react",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "tsx" to "react",
        "go" to "go", "goose" to "goose", "rs" to "rust", "java" to "java",
        "kt" to "kotlin", "kts" to "kotlin",
        "c" to "c", "h" to "cplusplus",
        "cpp" to "cplusplus", "cc" to "cplusplus", "cxx" to "cplusplus", "c++" to "cplusplus", "hpp" to "cplusplus", "hh" to "cplusplus", "hxx" to "cplusplus", "h++" to "cplusplus",
        "cs" to "csharp", "csx" to "csharp", "csproj" to "csharp", "fs" to "fsharp", "fsi" to "fsharp", "fsx" to "fsharp", "fsproj" to "fsharp",
        "php" to "php", "phtml" to "php", "rb" to "ruby", "erb" to "ruby",
        "swift" to "swift", "dart" to "dart",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "fish" to "shell",
        "ps1" to "powershell", "psm1" to "powershell", "psd1" to "powershell", "bat" to "bat", "cmd" to "bat",
        "html" to "html", "htm" to "html", "xhtml" to "html",
        "css" to "css", "scss" to "sass", "sass" to "sass", "less" to "less",
        "json" to "json", "json5" to "json", "jsonc" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "xml" to "xml", "xsd" to "xml", "xsl" to "xml", "xslt" to "xml", "rng" to "xml", "dtd" to "xml", "plist" to "xml",
        "md" to "markdown", "mdx" to "markdown", "markdown" to "markdown",
        "toml" to "toml", "ini" to "ini", "cfg" to "ini", "conf" to "ini", "properties" to "properties", "env" to "env",
        "sql" to "sql", "sqlite" to "database", "sqlite3" to "database", "db" to "database",
        "r" to "r", "lua" to "lua", "luau" to "lua", "pl" to "perl", "pm" to "perl",
        "hs" to "haskell", "lhs" to "haskell", "scala" to "scala", "sc" to "scala", "sbt" to "scala",
        "ex" to "elixir", "exs" to "elixir", "erl" to "erlang", "hrl" to "erlang", "clj" to "clojure", "cljs" to "clojure",
        "cljc" to "clojure", "edn" to "clojure", "bb" to "clojure", "cljd" to "clojure", "clj_kondo" to "clojure",
        "zig" to "zig", "zon" to "zig", "nim" to "nim", "nims" to "nim", "nimble" to "nim",
        "vue" to "vue", "svelte" to "svelte",
        "graphql" to "graphql", "gql" to "graphql",
        "sol" to "solidity", "m" to "objectivec", "mm" to "objectivec",
        "asm" to "assembly", "s" to "assembly",
        "vim" to "vim", "tex" to "tex", "sty" to "tex", "cls" to "tex", "lco" to "tex", "bib" to "tex", "bibtex" to "tex",
        "prisma" to "prisma", "tf" to "terraform", "tfvars" to "terraform", "proto" to "protobuf",
        "gradle" to "gradle", "cmake" to "cmake", "mk" to "makefile", "mak" to "makefile", "makefile" to "makefile",
        "dockerfile" to "docker",
        "png" to "image", "jpg" to "image", "jpeg" to "image", "gif" to "image", "webp" to "image", "bmp" to "image", "svg" to "image", "ico" to "image", "heic" to "image", "avif" to "image",
        "mp4" to "video", "mkv" to "video", "mov" to "video", "webm" to "video", "avi" to "video", "m4v" to "video", "mpeg" to "video", "mpg" to "video", "3gp" to "video",
        "mp3" to "audio", "wav" to "audio", "ogg" to "audio", "flac" to "audio", "m4a" to "audio", "aac" to "audio", "opus" to "audio", "aiff" to "audio", "wma" to "audio",
        "zip" to "zip", "tar" to "zip", "gz" to "zip", "bz2" to "zip", "xz" to "zip", "7z" to "zip", "rar" to "zip", "tgz" to "zip", "zst" to "zip", "lz4" to "zip", "deb" to "zip", "rpm" to "zip",
        "pdf" to "pdf", "docx" to "word", "doc" to "word", "docm" to "word", "dot" to "word", "dotx" to "word", "dotm" to "word", "odt" to "word", "rtf" to "word",
        "lock" to "lock", "lockb" to "lock", "log" to "log", "txt" to "text",
        "jar" to "archive", "aar" to "archive", "apk" to "android", "aab" to "android",
        "wasm" to "webassembly", "wat" to "webassembly",
        "csv" to "table", "tsv" to "table", "psv" to "table", "xls" to "table", "xlsx" to "table", "xlsm" to "table", "xlsb" to "table", "ods" to "table",
        "ppt" to "powerpoint", "pptx" to "powerpoint", "pptm" to "powerpoint", "pps" to "powerpoint", "ppsx" to "powerpoint", "odp" to "powerpoint",
        "ipynb" to "jupyter", "ttf" to "font", "otf" to "font", "woff" to "font", "woff2" to "font", "eot" to "font",
        "crt" to "certificate", "cer" to "certificate", "cert" to "certificate", "der" to "certificate", "p12" to "certificate", "pfx" to "certificate", "csr" to "certificate",
        "key" to "key", "pub" to "key", "pem" to "key", "asc" to "key", "exe" to "exe", "msi" to "exe",
        "bin" to "binary", "elf" to "binary", "o" to "binary", "a" to "binary", "so" to "binary", "dll" to "binary", "dylib" to "binary",
        "class" to "java", "dex" to "android", "smali" to "android", "diff" to "diff", "patch" to "diff",
        "astro" to "astro", "nix" to "nix", "jl" to "julia", "ml" to "ocaml", "mli" to "ocaml",
        "scm" to "scheme", "ss" to "scheme", "rkt" to "racket", "f" to "fortran", "for" to "fortran", "f90" to "fortran", "f95" to "fortran", "f03" to "fortran", "f08" to "fortran",
        "cob" to "cobol", "cbl" to "cobol", "pas" to "pascal", "coffee" to "coffee", "cson" to "coffee", "iced" to "coffee", "styl" to "stylus",
    )

    internal val registeredIconNames: Set<String>
        get() = fileNames.values.toSet() + compoundSuffixes.map { it.second } + extensions.values

    fun iconNameFor(path: String): String {
        val leaf = path.substringAfterLast('/').substringAfterLast('\\')
        val name = leaf.lowercase()
        fileNames[name]?.let { return it }
        compoundSuffixes.firstOrNull { (suffix, _) -> name.endsWith(suffix) }?.let { return it.second }
        // Uppercase .C/.H are C++ source/header conventions, also used by the language registry.
        val extension = leaf.substringAfterLast('.', "")
        if (extension == "C" || extension == "H") return "cplusplus"
        return extensions[extension.lowercase()] ?: "default_file"
    }

     
    fun fallbackToken(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val icon = iconNameFor(path)
        if (icon != "default_file") return when (icon) {
            "javascript" -> "JS"; "typescript" -> "TS"; "cplusplus" -> "C++"; "csharp" -> "C#"
            "powershell" -> "PS"; "graphql" -> "GQL"; "terraform" -> "TF"; "protobuf" -> "PB"
            "word" -> "DOC"; "objectivec" -> "OC"; "assembly" -> "ASM"; "markdown" -> "MD"; "properties" -> "CFG"
            else -> icon.take(3).uppercase()
        }
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        return (ext ?: name.take(2)).take(3).uppercase().ifBlank { "FILE" }
    }

    fun isTextFile(path: String): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name in fileNames) return true
        val ext = name.substringAfterLast('.', "")
        return ext !in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif",
            "mp4", "mkv", "mov", "webm", "mp3", "wav", "ogg", "flac",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "pdf", "doc", "docx", "docm", "dot", "dotx", "dotm",
            "jar", "aar", "apk", "aab", "wasm", "so", "dex", "class", "o", "a", "bin", "exe",
        )
    }

    fun isImageFile(path: String): Boolean = iconNameFor(path) == "image"
}

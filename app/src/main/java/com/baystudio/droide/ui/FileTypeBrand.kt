package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R
import com.baystudio.droide.core.FileIconRegistry








@DrawableRes
fun fileTypeResource(path: String): Int? = fileTypeResourceForIcon(FileIconRegistry.iconNameFor(path))

@DrawableRes
internal fun fileTypeResourceForIcon(iconName: String): Int? = when (iconName) {
    "pdf" -> R.drawable.filetype_pdf
    "word" -> R.drawable.filetype_word
    "android" -> R.drawable.filetype_android
    "gradle" -> R.drawable.filetype_gradle
    "kotlin" -> R.drawable.filetype_kotlin
    "markdown" -> R.drawable.filetype_markdown
    "xml" -> R.drawable.filetype_xml
    "toml" -> R.drawable.filetype_toml
    "java" -> R.drawable.filetype_java
    "cplusplus" -> R.drawable.filetype_cplusplus
    "c" -> R.drawable.filetype_c
    "csharp" -> R.drawable.filetype_csharp
    "python" -> R.drawable.filetype_python
    "javascript" -> R.drawable.filetype_javascript
    "typescript" -> R.drawable.filetype_typescript
    "react" -> R.drawable.filetype_react
    "go" -> R.drawable.filetype_go
    "goose" -> R.drawable.filetype_goose
    "rust" -> R.drawable.filetype_rust
    "php" -> R.drawable.filetype_php
    "ruby" -> R.drawable.filetype_ruby
    "swift" -> R.drawable.filetype_swift
    "dart" -> R.drawable.filetype_dart
    "html" -> R.drawable.filetype_html
    "css" -> R.drawable.filetype_css
    "json" -> R.drawable.filetype_json
    "yaml" -> R.drawable.filetype_yaml
    "lua" -> R.drawable.filetype_lua
    "powershell" -> R.drawable.filetype_powershell
    "docker" -> R.drawable.filetype_docker
    "makefile" -> R.drawable.filetype_makefile
    "vue" -> R.drawable.filetype_vue
    "svelte" -> R.drawable.filetype_svelte
    "graphql" -> R.drawable.filetype_graphql
    "terraform" -> R.drawable.filetype_terraform
    "shell" -> R.drawable.filetype_console
    "bat" -> R.drawable.filetype_console
    "sql" -> R.drawable.filetype_database
    "database" -> R.drawable.filetype_database
    "r" -> R.drawable.filetype_r
    "perl" -> R.drawable.filetype_perl
    "haskell" -> R.drawable.filetype_haskell
    "scala" -> R.drawable.filetype_scala
    "elixir" -> R.drawable.filetype_elixir
    "erlang" -> R.drawable.filetype_erlang
    "clojure" -> R.drawable.filetype_clojure
    "zig" -> R.drawable.filetype_zig
    "nim" -> R.drawable.filetype_nim
    "solidity" -> R.drawable.filetype_solidity
    "objectivec" -> R.drawable.filetype_objectivec
    "assembly" -> R.drawable.filetype_assembly
    "vim" -> R.drawable.filetype_vim
    "tex" -> R.drawable.filetype_tex
    "ini" -> R.drawable.filetype_settings
    "properties" -> R.drawable.filetype_settings
    "settings" -> R.drawable.filetype_settings
    "env" -> R.drawable.filetype_env
    "prisma" -> R.drawable.filetype_prisma
    "protobuf" -> R.drawable.filetype_protobuf
    "archive" -> R.drawable.filetype_archive
    "audio" -> R.drawable.filetype_audio
    "cmake" -> R.drawable.filetype_cmake
    "fsharp" -> R.drawable.filetype_fsharp
    "git" -> R.drawable.filetype_git
    "image" -> R.drawable.filetype_image
    "less" -> R.drawable.filetype_less
    "license" -> R.drawable.filetype_license
    "lock" -> R.drawable.filetype_lock
    "log" -> R.drawable.filetype_log
    "sass" -> R.drawable.filetype_sass
    "text" -> R.drawable.filetype_text
    "video" -> R.drawable.filetype_video
    "webassembly" -> R.drawable.filetype_webassembly
    "zip" -> R.drawable.filetype_zip
    "table" -> R.drawable.filetype_table
    "powerpoint" -> R.drawable.filetype_powerpoint
    "font" -> R.drawable.filetype_font
    "jupyter" -> R.drawable.filetype_jupyter
    "exe", "binary" -> R.drawable.filetype_exe
    "certificate" -> R.drawable.filetype_certificate
    "key" -> R.drawable.filetype_key
    "nodejs" -> R.drawable.filetype_nodejs
    "npm" -> R.drawable.filetype_npm
    "yarn" -> R.drawable.filetype_yarn
    "gomod" -> R.drawable.filetype_gomod
    "eslint" -> R.drawable.filetype_eslint
    "prettier" -> R.drawable.filetype_prettier
    "tsconfig" -> R.drawable.filetype_tsconfig
    "vite" -> R.drawable.filetype_vite
    "astro" -> R.drawable.filetype_astro
    "nix" -> R.drawable.filetype_nix
    "julia" -> R.drawable.filetype_julia
    "ocaml" -> R.drawable.filetype_ocaml
    "scheme" -> R.drawable.filetype_scheme
    "racket" -> R.drawable.filetype_racket
    "fortran" -> R.drawable.filetype_fortran
    "cobol" -> R.drawable.filetype_cobol
    "pascal" -> R.drawable.filetype_pascal
    "coffee" -> R.drawable.filetype_coffee
    "stylus" -> R.drawable.filetype_stylus
    "diff" -> R.drawable.filetype_diff
    else -> null
}

@Deprecated("Use fileTypeResource so every workbench surface shares the global resolver")
@DrawableRes
fun prototypeFileTypeResource(path: String): Int? = fileTypeResource(path)

@Composable
fun FileTypeBrandIcon(
    path: String,
    contentDescription: String? = null,
    size: Dp = 17.dp,
    modifier: Modifier = Modifier,
    fallbackTint: Color = DroideColors.Muted,
) {
    val resource = fileTypeResource(path)
    if (resource == R.drawable.filetype_goose) {
        GooseFileIcon(contentDescription, modifier.size(size))
        return
    }
    if (resource != null) {
        Image(
            painter = painterResource(resource),
            contentDescription = contentDescription ?: "${FileIconRegistry.iconNameFor(path)} file",
            modifier = modifier.size(size),
        )
        return
    }

    Icon(
        imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
        contentDescription = contentDescription ?: "File",
        modifier = modifier.size(size),
        tint = fallbackTint,
    )
}






@Composable
private fun GooseFileIcon(description: String?, modifier: Modifier) {
    Icon(painterResource(R.drawable.filetype_goose), description ?: "goose file", modifier,
        tint = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun FileIdentityLabel(
    path: String,
    label: String = path,
    modifier: Modifier = Modifier,
    iconSize: Dp = 15.dp,
    maxLines: Int = 1,
    monospaced: Boolean = false,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FileTypeBrandIcon(path = path, size = iconSize)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
            fontFamily = if (monospaced) FontFamily.Monospace else null,
            color = color,
            style = style,
        )
    }
}

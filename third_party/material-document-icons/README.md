# Material Icon Theme file artwork

File-type artwork from Material Extensions, pinned to commit `13025adc5ad10a5cc649528a2c1edec93069f1b9`. The bundle covers language, configuration, document, media, archive, font, and security file types. Exact original SVGs, upstream LICENSE and upstream README are retained. The manifest records immutable source URLs and SHA-256 hashes for every asset.

Copyright (c) 2025 Material Extensions. The upstream repository publishes the MIT License, reproduced in `LICENSE` and in the application's legal assets. Its README credits Material Design Icons and Material Symbols.

`python tools/generate_document_file_icons.py` makes the Android resources; `--check` compares without writing. The conversion preserves source path data, viewport dimensions, translations, and fill colors. Circles become exact circular arc paths. Short hex colors are expanded; unpainted group stroke widths have no effect. Unsupported geometry or styling fails conversion rather than silently dropping it. Word is deliberately recolored from upstream #01579b to #1e88e5 to keep its small W readable: solid-fill contrast is 4.46:1 on tab #1e1f26, 4.70:1 on sidebar #161b22 and 3.68:1 on white. These ratios are a presentation check, not full accessibility certification. No shape is invented or rasterized.

These are Material Icon Theme file-type representations, not official Adobe or Microsoft assets. All product, project, and language marks remain with their owners. Copyright licensing does not grant trademark rights, vendor endorsement or public-release clearance. Use is solely to identify file types in the existing file-icon UI.

The shared file registry selects an exact filename first, then a compound suffix, then a file extension. No file-content reads, directory scans, or runtime network requests are needed. Icon selection does not add viewer or execution capabilities. Tests compare the registry with the built-in editor and language-server catalogs and require an Android resource for every registered icon category.

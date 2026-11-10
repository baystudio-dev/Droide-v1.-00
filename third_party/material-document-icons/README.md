# Material Icon Theme document file artwork

PDF and Word file-type artwork from Material Extensions, pinned to commit `13025adc5ad10a5cc649528a2c1edec93069f1b9`. Exact original SVGs, upstream LICENSE and upstream README are retained. The manifest records immutable source URLs and SHA-256 hashes.

Copyright (c) 2025 Material Extensions. The upstream repository publishes the MIT License, reproduced in `LICENSE` and in the application's legal assets. Its README credits Material Design Icons and Material Symbols.

`python tools/generate_document_file_icons.py` makes the Android resources; `--check` compares without writing. The conversion preserves each source's single path and 24×24 viewport. PDF stays #ef5350. Word is deliberately recolored from upstream #01579b to #1e88e5 to keep its small W readable: solid-fill contrast is 4.46:1 on tab #1e1f26, 4.70:1 on sidebar #161b22 and 3.68:1 on white. These ratios are a presentation check, not full accessibility certification. No shape is invented or rasterized.

These are Material Icon Theme file-type representations, not official Adobe or Microsoft assets. Adobe/Acrobat/PDF and Microsoft/Word marks remain with their owners. Copyright licensing does not grant trademark rights, vendor endorsement or public-release clearance. Use is solely to identify PDF and DOCX files in the existing file-icon UI.

Only `.pdf` and `.docx` route to these assets. Other document formats and viewer capabilities are unchanged.

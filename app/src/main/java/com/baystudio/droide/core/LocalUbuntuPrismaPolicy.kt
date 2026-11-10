package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Exact public registry metadata, reviewed 2026-10-18. This policy does not certify runtime acceptance. */
internal object LocalUbuntuPrismaPolicy {
    const val FAMILY_ID = "lsp.prisma-language-server"
    const val VERSION = "31.13.0"
    const val PACKAGE = "@prisma/language-server"
    const val COMMAND = "prisma-language-server"
    const val PROVENANCE = "https://github.com/prisma/language-tools"
    const val INTEGRITY = "sha512-3ATe7MSnR4mFBCaiDyvNDrjWtfbz15y0guzGQ8oXOzJDBnHE4AzVxGobP7PgrWxsqry1CCY0x7/FfExZzAHKAA=="
    const val TARBALL = "https://registry.npmjs.org/@prisma/language-server/-/language-server-31.13.0.tgz"
    const val CONTRACT_SHA256 = "6de1f7e4710d74447f40b56e700e2f6d72fec0e7daad211f72eaede4ef0d202d"
    const val MAX_JSON_BYTES = 8_000_000
    val closureJson = """{"artifacts":{"@babel/helper-string-parser":{"version":"7.29.7","resolved":"https://registry.npmjs.org/@babel/helper-string-parser/-/helper-string-parser-7.29.7.tgz","integrity":"sha512-Pb5ijPrZ89GDH8223L4UP8i6QApWxs04RbPQJTeWDV0/keR2E36MeKnyr6LYmUUvqRRI+Iv87SuF1W6ErINzYw=="},"@babel/helper-validator-identifier":{"version":"7.29.7","resolved":"https://registry.npmjs.org/@babel/helper-validator-identifier/-/helper-validator-identifier-7.29.7.tgz","integrity":"sha512-qehxGkRj55h/ff8EMaJ+cYhyaKlHIxqYDn682wQD7RNp9UujOQsHog2uS0r2vzr4pW+sXf90NeeayjcNaX3fFg=="},"@babel/parser":{"version":"7.29.8","resolved":"https://registry.npmjs.org/@babel/parser/-/parser-7.29.8.tgz","integrity":"sha512-E8lTAYNB1KW+FH+VGJuZM1ioAx2E6oVlvQFRrf5P8ZZmsiJXYAD9vTFV7yyEURNzgh1dFqMZuO6tUwcARbqFCA=="},"@babel/types":{"version":"7.29.8","resolved":"https://registry.npmjs.org/@babel/types/-/types-7.29.8.tgz","integrity":"sha512-Vj1jF3cPfxg7OAfoI7QnVKLoILlm2JF9pnVHrX8qx7AHMiYWT+NDAA7jChlNgRS4WTLc/fD1lXLmPixluj+3Gg=="},"@prisma/config":{"version":"7.9.0-dev.4","resolved":"https://registry.npmjs.org/@prisma/config/-/config-7.9.0-dev.4.tgz","integrity":"sha512-/8Oq1I48iCEXjkwsXvwTrVQmH+I7s6FSRvYomm5VEMh7L9ZtNZkwElVek/Eaxjv+iRu2J3upDTrQuLmL/Vq7AQ=="},"@prisma/language-server":{"version":"31.13.0","resolved":"https://registry.npmjs.org/@prisma/language-server/-/language-server-31.13.0.tgz","integrity":"sha512-3ATe7MSnR4mFBCaiDyvNDrjWtfbz15y0guzGQ8oXOzJDBnHE4AzVxGobP7PgrWxsqry1CCY0x7/FfExZzAHKAA=="},"@prisma/prisma-schema-wasm":{"version":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","resolved":"https://registry.npmjs.org/@prisma/prisma-schema-wasm/-/prisma-schema-wasm-7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a.tgz","integrity":"sha512-yyKNwOSaxExepEI+pWwESbDNRSIhzE53PXD6YaEvY7cNl39qOIMjWJCIXMDwZQpt5ixDkL/sesWYg3XxCmBkZQ=="},"@prisma/schema-files-loader":{"version":"7.9.0-dev.4","resolved":"https://registry.npmjs.org/@prisma/schema-files-loader/-/schema-files-loader-7.9.0-dev.4.tgz","integrity":"sha512-APiVjNmgHcJ4+mUxbR9JhhqN3wgXoVHr9xBdL/w2ATBLiioMts91Oit2ePRQWGqgqnW918INessR5jazYYpkZg=="},"@standard-schema/spec":{"version":"1.0.0","resolved":"https://registry.npmjs.org/@standard-schema/spec/-/spec-1.0.0.tgz","integrity":"sha512-m2bOd0f2RT9k8QJx1JN85cZYyH1RqFBdlwtkSlf4tBDYLCiiZnv1fIIwacK6cqwXavOydf0NPToMQgpKq+dVlA=="},"@types/js-levenshtein":{"version":"1.1.3","resolved":"https://registry.npmjs.org/@types/js-levenshtein/-/js-levenshtein-1.1.3.tgz","integrity":"sha512-jd+Q+sD20Qfu9e2aEXogiO3vpOC1PYJOUdyN9gvs4Qrvkg4wF43L5OhqrPeokdv8TL0/mXoYfpkcoGZMNN2pkQ=="},"c12":{"version":"3.3.4","resolved":"https://registry.npmjs.org/c12/-/c12-3.3.4.tgz","integrity":"sha512-cM0ApFQSBXuourJejzwv/AuPRvAxordTyParRVcHjjtXirtkzM0uK2L9TTn9s0cXZbG7E55jCivRQzoxYmRAlA=="},"chokidar":{"version":"5.0.0","resolved":"https://registry.npmjs.org/chokidar/-/chokidar-5.0.0.tgz","integrity":"sha512-TQMmc3w+5AxjpL8iIiwebF73dRDF4fBIieAqGn9RGCWaEVwQ6Fb2cGe31Yns0RRIzii5goJ1Y7xbMwo1TxMplw=="},"confbox":{"version":"0.2.4","resolved":"https://registry.npmjs.org/confbox/-/confbox-0.2.4.tgz","integrity":"sha512-ysOGlgTFbN2/Y6Cg3Iye8YKulHw+R2fNXHrgSmXISQdMnomY6eNDprVdW9R5xBguEqI954+S6709UyiO7B+6OQ=="},"deepmerge-ts":{"version":"7.1.5","resolved":"https://registry.npmjs.org/deepmerge-ts/-/deepmerge-ts-7.1.5.tgz","integrity":"sha512-HOJkrhaYsweh+W+e74Yn7YStZOilkoPb6fycpwNLKzSPtruFs48nYis0zy5yJz1+ktUhHxoRDJ27RQAWLIJVJw=="},"defu":{"version":"6.1.7","resolved":"https://registry.npmjs.org/defu/-/defu-6.1.7.tgz","integrity":"sha512-7z22QmUWiQ/2d0KkdYmANbRUVABpZ9SNYyH5vx6PZ+nE5bcC0l7uFvEfHlyld/HcGBFTL536ClDt3DEcSlEJAQ=="},"destr":{"version":"2.0.5","resolved":"https://registry.npmjs.org/destr/-/destr-2.0.5.tgz","integrity":"sha512-ugFTXCtDZunbzasqBxrK93Ik/DRYsO6S/fedkWEMKqt04xZ4csmnmwGDBAb07QWNaGMAmnTIemsYZCksjATwsA=="},"dotenv":{"version":"17.4.1","resolved":"https://registry.npmjs.org/dotenv/-/dotenv-17.4.1.tgz","integrity":"sha512-k8DaKGP6r1G30Lx8V4+pCsLzKr8vLmV2paqEj1Y55GdAgJuIqpRp5FfajGF8KtwMxCz9qJc6wUIJnm053d/WCw=="},"effect":{"version":"3.20.0","resolved":"https://registry.npmjs.org/effect/-/effect-3.20.0.tgz","integrity":"sha512-qMLfDJscrNG8p/aw+IkT9W7fgj50Z4wG5bLBy0Txsxz8iUHjDIkOgO3SV0WZfnQbNG2VJYb0b+rDLMrhM4+Krw=="},"empathic":{"version":"2.0.0","resolved":"https://registry.npmjs.org/empathic/-/empathic-2.0.0.tgz","integrity":"sha512-i6UzDscO/XfAcNYD75CfICkmfLedpyPDdozrLMmQc5ORaQcdMoc21OnlEylMIqI7U8eniKrPMxxtj8k0vhmJhA=="},"exsolve":{"version":"1.0.8","resolved":"https://registry.npmjs.org/exsolve/-/exsolve-1.0.8.tgz","integrity":"sha512-LmDxfWXwcTArk8fUEnOfSZpHOJ6zOMUJKOtFLFqJLoKJetuQG874Uc7/Kki7zFLzYybmZhp1M7+98pfMqeX8yA=="},"fast-check":{"version":"3.23.2","resolved":"https://registry.npmjs.org/fast-check/-/fast-check-3.23.2.tgz","integrity":"sha512-h5+1OzzfCC3Ef7VbtKdcv7zsstUQwUDlYpUTvjeUsJAssPgLn7QzbboPtL5ro04Mq0rPOsMzl7q5hIbRs2wD1A=="},"fs-extra":{"version":"11.3.0","resolved":"https://registry.npmjs.org/fs-extra/-/fs-extra-11.3.0.tgz","integrity":"sha512-Z4XaCL6dUDHfP/jT25jJKMmtxvuwbkrD1vNSMFlo9lNLY2c5FHYSQgHPRZUjAB26TpDEoW9HCOgplrdbaPV/ew=="},"giget":{"version":"3.2.0","resolved":"https://registry.npmjs.org/giget/-/giget-3.2.0.tgz","integrity":"sha512-GvHTWcykIR/fP8cj8dMpuMMkvaeJfPvYnhq0oW+chSeIr+ldX21ifU2Ms6KBoyKZQZmVaUAAhQ2EZ68KJF8a7A=="},"graceful-fs":{"version":"4.2.11","resolved":"https://registry.npmjs.org/graceful-fs/-/graceful-fs-4.2.11.tgz","integrity":"sha512-RbJ5/jmFcNNCcDV5o9eTnBLJ/HszWV0P73bc+Ff4nS/rJj+YaS6IGyiOL0VoBYX+l1Wrl3k63h/KrH+nhJ0XvQ=="},"jiti":{"version":"2.6.1","resolved":"https://registry.npmjs.org/jiti/-/jiti-2.6.1.tgz","integrity":"sha512-ekilCSN1jwRvIbgeg/57YFh8qQDNbwDb9xT/qu2DAHbFFZUicIl4ygVaAvzveMhMVr3LnpSKTNnwt8PoOfmKhQ=="},"js-levenshtein":{"version":"1.1.6","resolved":"https://registry.npmjs.org/js-levenshtein/-/js-levenshtein-1.1.6.tgz","integrity":"sha512-X2BB11YZtrRqY4EnQcLX5Rh373zbK4alC1FW7D7MBhL2gtcC17cTnr6DmfHZeS0s2rTHjUTMMHfG7gO8SSdw+g=="},"jsonfile":{"version":"6.2.0","resolved":"https://registry.npmjs.org/jsonfile/-/jsonfile-6.2.0.tgz","integrity":"sha512-FGuPw30AdOIUTRMC2OMRtQV+jkVj2cfPqSeWXv1NEAJ1qZ5zb1X6z1mFhbfOB/iy3ssJCD+3KuZ8r8C3uVFlAg=="},"klona":{"version":"2.0.6","resolved":"https://registry.npmjs.org/klona/-/klona-2.0.6.tgz","integrity":"sha512-dhG34DXATL5hSxJbIexCft8FChFXtmskoZYnoPWjXQuebWYCNkVeV3KkGegCK9CP1oswI/vQibS2GY7Em/sJJA=="},"magicast":{"version":"0.5.4","resolved":"https://registry.npmjs.org/magicast/-/magicast-0.5.4.tgz","integrity":"sha512-llBEhWm1SacoRwgHUoQJYtwp4PBLF4faQi5TCpIGyGs9n4y5+juI0tDgyKIfpqxckRHaHzouUEph3THklWh03w=="},"ohash":{"version":"2.0.11","resolved":"https://registry.npmjs.org/ohash/-/ohash-2.0.11.tgz","integrity":"sha512-RdR9FQrFwNBNXAr4GixM8YaRZRJ5PUWbKYbE5eOsrwAjJW0q2REGcf79oYPsLyskQCZG1PLN+S/K1V00joZAoQ=="},"pathe":{"version":"2.0.3","resolved":"https://registry.npmjs.org/pathe/-/pathe-2.0.3.tgz","integrity":"sha512-WUjGcAqP1gQacoQe+OBJsFA7Ld4DyXuUIjZ5cc75cLHvJ7dtNsTugphxIADwspS+AraAUePCKrSVtPLFj/F88w=="},"perfect-debounce":{"version":"2.1.0","resolved":"https://registry.npmjs.org/perfect-debounce/-/perfect-debounce-2.1.0.tgz","integrity":"sha512-LjgdTytVFXeUgtHZr9WYViYSM/g8MkcTPYDlPa3cDqMirHjKiSZPYd6DoL7pK8AJQr+uWkQvCjHNdiMqsrJs+g=="},"pkg-types":{"version":"2.3.0","resolved":"https://registry.npmjs.org/pkg-types/-/pkg-types-2.3.0.tgz","integrity":"sha512-SIqCzDRg0s9npO5XQ3tNZioRY1uK06lA41ynBC1YmFTmnY6FjUjVt6s4LoADmwoig1qqD0oK8h1p/8mlMx8Oig=="},"pure-rand":{"version":"6.1.0","resolved":"https://registry.npmjs.org/pure-rand/-/pure-rand-6.1.0.tgz","integrity":"sha512-bVWawvoZoBYpp6yIoQtQXHZjmz35RSVHnUOTefl8Vcjr8snTPY1wnpSPMWekcFwbxI6gtmT7rSYPFvz71ldiOA=="},"rc9":{"version":"3.0.1","resolved":"https://registry.npmjs.org/rc9/-/rc9-3.0.1.tgz","integrity":"sha512-gMDyleLWVE+i6Sgtc0QbbY6pEKqYs97NGi6isHQPqYlLemPoO8dxQ3uGi0f4NiP98c+jMW6cG1Kx9dDwfvqARQ=="},"readdirp":{"version":"5.0.0","resolved":"https://registry.npmjs.org/readdirp/-/readdirp-5.0.0.tgz","integrity":"sha512-9u/XQ1pvrQtYyMpZe7DXKv2p5CNvyVwzUB6uhLAnQwHMSgKMBR62lc7AHljaeteeHXn11XTAaLLUVZYVZyuRBQ=="},"source-map-js":{"version":"1.2.1","resolved":"https://registry.npmjs.org/source-map-js/-/source-map-js-1.2.1.tgz","integrity":"sha512-UXWMKhLOwVKb728IUtQPXxfYU+usdybtUrK/8uGE8CQMvrhOpwvzDBwj0QhSL7MQc7vIsISBG8VQ8+IDQxpfQA=="},"universalify":{"version":"2.0.1","resolved":"https://registry.npmjs.org/universalify/-/universalify-2.0.1.tgz","integrity":"sha512-gptHNQghINnc/vTGIk0SOFGFNXw7JVrlRUtConJRlvaw6DuX0wO5Jeko9sWrMBhh+PsYAZ7oXAiOnf/UKogyiw=="},"vscode-jsonrpc":{"version":"8.1.0","resolved":"https://registry.npmjs.org/vscode-jsonrpc/-/vscode-jsonrpc-8.1.0.tgz","integrity":"sha512-6TDy/abTQk+zDGYazgbIPc+4JoXdwC8NHU9Pbn4UJP1fehUyZmM4RHp5IthX7A6L5KS30PRui+j+tbbMMMafdw=="},"vscode-languageserver":{"version":"8.1.0","resolved":"https://registry.npmjs.org/vscode-languageserver/-/vscode-languageserver-8.1.0.tgz","integrity":"sha512-eUt8f1z2N2IEUDBsKaNapkz7jl5QpskN2Y0G01T/ItMxBxw1fJwvtySGB9QMecatne8jFIWJGWI61dWjyTLQsw=="},"vscode-languageserver-protocol":{"version":"3.17.3","resolved":"https://registry.npmjs.org/vscode-languageserver-protocol/-/vscode-languageserver-protocol-3.17.3.tgz","integrity":"sha512-924/h0AqsMtA5yK22GgMtCYiMdCOtWTSGgUOkgEDX+wk2b0x4sAfLiO4NxBxqbiVtz7K7/1/RgVrVI0NClZwqA=="},"vscode-languageserver-textdocument":{"version":"1.0.11","resolved":"https://registry.npmjs.org/vscode-languageserver-textdocument/-/vscode-languageserver-textdocument-1.0.11.tgz","integrity":"sha512-X+8T3GoiwTVlJbicx/sIAF+yuJAqz8VvwJyoMVhwEMoEKE/fkDmrqUgDMyBECcM2A2frVZIUj5HI/ErRXCfOeA=="},"vscode-languageserver-types":{"version":"3.17.3","resolved":"https://registry.npmjs.org/vscode-languageserver-types/-/vscode-languageserver-types-3.17.3.tgz","integrity":"sha512-SYU4z1dL0PyIMd4Vj8YOqFvHu7Hz/enbWtpfnVbJHU4Nd1YNYx8u0ennumc6h48GQNeOLxmwySmnADouT/AuZA=="},"vscode-uri":{"version":"3.1.0","resolved":"https://registry.npmjs.org/vscode-uri/-/vscode-uri-3.1.0.tgz","integrity":"sha512-/BpdSx+yCQGnCvecbyXdxHDkuk55/G3xwnC0GqY4gmQ3j+A+g8kzzgB4Nk/SINjqn6+waqw3EgbVF2QKExkRxQ=="}},"additionalDependencies":{"@babel/helper-string-parser":"7.29.7","@babel/helper-validator-identifier":"7.29.7","@babel/parser":"7.29.8","@babel/types":"7.29.8","@prisma/config":"7.9.0-dev.4","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","@prisma/schema-files-loader":"7.9.0-dev.4","@standard-schema/spec":"1.0.0","@types/js-levenshtein":"1.1.3","c12":"3.3.4","chokidar":"5.0.0","confbox":"0.2.4","deepmerge-ts":"7.1.5","defu":"6.1.7","destr":"2.0.5","dotenv":"17.4.1","effect":"3.20.0","empathic":"2.0.0","exsolve":"1.0.8","fast-check":"3.23.2","fs-extra":"11.3.0","giget":"3.2.0","graceful-fs":"4.2.11","jiti":"2.6.1","js-levenshtein":"1.1.6","jsonfile":"6.2.0","klona":"2.0.6","magicast":"0.5.4","ohash":"2.0.11","pathe":"2.0.3","perfect-debounce":"2.1.0","pkg-types":"2.3.0","pure-rand":"6.1.0","rc9":"3.0.1","readdirp":"5.0.0","source-map-js":"1.2.1","universalify":"2.0.1","vscode-jsonrpc":"8.1.0","vscode-languageserver":"8.1.0","vscode-languageserver-protocol":"3.17.3","vscode-languageserver-textdocument":"1.0.11","vscode-languageserver-types":"3.17.3","vscode-uri":"3.1.0"},"expectedLockPaths":["","node_modules/@babel/helper-string-parser","node_modules/@babel/helper-validator-identifier","node_modules/@babel/parser","node_modules/@babel/types","node_modules/@prisma/config","node_modules/@prisma/language-server","node_modules/@prisma/prisma-schema-wasm","node_modules/@prisma/schema-files-loader","node_modules/@standard-schema/spec","node_modules/@types/js-levenshtein","node_modules/c12","node_modules/chokidar","node_modules/confbox","node_modules/deepmerge-ts","node_modules/defu","node_modules/destr","node_modules/dotenv","node_modules/effect","node_modules/empathic","node_modules/exsolve","node_modules/fast-check","node_modules/fs-extra","node_modules/giget","node_modules/graceful-fs","node_modules/jiti","node_modules/js-levenshtein","node_modules/jsonfile","node_modules/klona","node_modules/magicast","node_modules/ohash","node_modules/pathe","node_modules/perfect-debounce","node_modules/pkg-types","node_modules/pure-rand","node_modules/rc9","node_modules/readdirp","node_modules/source-map-js","node_modules/universalify","node_modules/vscode-jsonrpc","node_modules/vscode-languageserver","node_modules/vscode-languageserver-protocol","node_modules/vscode-languageserver-textdocument","node_modules/vscode-languageserver-types","node_modules/vscode-uri"],"rootDependencies":{"@prisma/language-server":"31.13.0","@babel/helper-string-parser":"7.29.7","@babel/helper-validator-identifier":"7.29.7","@babel/parser":"7.29.8","@babel/types":"7.29.8","@prisma/config":"7.9.0-dev.4","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","@prisma/schema-files-loader":"7.9.0-dev.4","@standard-schema/spec":"1.0.0","@types/js-levenshtein":"1.1.3","c12":"3.3.4","chokidar":"5.0.0","confbox":"0.2.4","deepmerge-ts":"7.1.5","defu":"6.1.7","destr":"2.0.5","dotenv":"17.4.1","effect":"3.20.0","empathic":"2.0.0","exsolve":"1.0.8","fast-check":"3.23.2","fs-extra":"11.3.0","giget":"3.2.0","graceful-fs":"4.2.11","jiti":"2.6.1","js-levenshtein":"1.1.6","jsonfile":"6.2.0","klona":"2.0.6","magicast":"0.5.4","ohash":"2.0.11","pathe":"2.0.3","perfect-debounce":"2.1.0","pkg-types":"2.3.0","pure-rand":"6.1.0","rc9":"3.0.1","readdirp":"5.0.0","source-map-js":"1.2.1","universalify":"2.0.1","vscode-jsonrpc":"8.1.0","vscode-languageserver":"8.1.0","vscode-languageserver-protocol":"3.17.3","vscode-languageserver-textdocument":"1.0.11","vscode-languageserver-types":"3.17.3","vscode-uri":"3.1.0"},"rootManifest":{"name":"droide-managed-agent","version":"1.0.0","private":true,"dependencies":{"@prisma/language-server":"31.13.0","@babel/helper-string-parser":"7.29.7","@babel/helper-validator-identifier":"7.29.7","@babel/parser":"7.29.8","@babel/types":"7.29.8","@prisma/config":"7.9.0-dev.4","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","@prisma/schema-files-loader":"7.9.0-dev.4","@standard-schema/spec":"1.0.0","@types/js-levenshtein":"1.1.3","c12":"3.3.4","chokidar":"5.0.0","confbox":"0.2.4","deepmerge-ts":"7.1.5","defu":"6.1.7","destr":"2.0.5","dotenv":"17.4.1","effect":"3.20.0","empathic":"2.0.0","exsolve":"1.0.8","fast-check":"3.23.2","fs-extra":"11.3.0","giget":"3.2.0","graceful-fs":"4.2.11","jiti":"2.6.1","js-levenshtein":"1.1.6","jsonfile":"6.2.0","klona":"2.0.6","magicast":"0.5.4","ohash":"2.0.11","pathe":"2.0.3","perfect-debounce":"2.1.0","pkg-types":"2.3.0","pure-rand":"6.1.0","rc9":"3.0.1","readdirp":"5.0.0","source-map-js":"1.2.1","universalify":"2.0.1","vscode-jsonrpc":"8.1.0","vscode-languageserver":"8.1.0","vscode-languageserver-protocol":"3.17.3","vscode-languageserver-textdocument":"1.0.11","vscode-languageserver-types":"3.17.3","vscode-uri":"3.1.0"}},"packageEdges":{"@babel/helper-string-parser":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@babel/helper-validator-identifier":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@babel/parser":{"dependencies":{"@babel/types":"^7.29.8"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@babel/types":{"dependencies":{"@babel/helper-string-parser":"^7.29.7","@babel/helper-validator-identifier":"^7.29.7"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@prisma/config":{"dependencies":{"c12":"3.3.4","effect":"3.20.0","empathic":"2.0.0","deepmerge-ts":"7.1.5"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@prisma/prisma-schema-wasm":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@prisma/schema-files-loader":{"dependencies":{"fs-extra":"11.3.0","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@standard-schema/spec":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@types/js-levenshtein":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"c12":{"dependencies":{"rc9":"^3.0.1","defu":"^6.1.6","jiti":"^2.6.1","giget":"^3.2.0","ohash":"^2.0.11","pathe":"^2.0.3","dotenv":"^17.3.1","confbox":"^0.2.4","exsolve":"^1.0.8","chokidar":"^5.0.0","pkg-types":"^2.3.0","perfect-debounce":"^2.1.0"},"optionalDependencies":{},"peerDependencies":{"magicast":"*"},"peerDependenciesMeta":{"magicast":{"optional":true}}},"chokidar":{"dependencies":{"readdirp":"^5.0.0"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"confbox":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"deepmerge-ts":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"defu":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"destr":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"dotenv":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"effect":{"dependencies":{"fast-check":"^3.23.1","@standard-schema/spec":"^1.0.0"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"empathic":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"exsolve":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"fast-check":{"dependencies":{"pure-rand":"^6.1.0"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"fs-extra":{"dependencies":{"jsonfile":"^6.0.1","graceful-fs":"^4.2.0","universalify":"^2.0.0"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"giget":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"graceful-fs":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"jiti":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"js-levenshtein":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"jsonfile":{"dependencies":{"universalify":"^2.0.0"},"optionalDependencies":{"graceful-fs":"^4.1.6"},"peerDependencies":{},"peerDependenciesMeta":{}},"klona":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"magicast":{"dependencies":{"@babel/types":"^7.29.7","@babel/parser":"^7.29.7","source-map-js":"^1.2.1"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"ohash":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"pathe":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"perfect-debounce":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"pkg-types":{"dependencies":{"pathe":"^2.0.3","confbox":"^0.2.2","exsolve":"^1.0.7"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"pure-rand":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"rc9":{"dependencies":{"defu":"^6.1.6","destr":"^2.0.5"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"readdirp":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"source-map-js":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"universalify":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-jsonrpc":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-languageserver-protocol":{"dependencies":{"vscode-jsonrpc":"8.1.0","vscode-languageserver-types":"3.17.3"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-languageserver-textdocument":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-languageserver-types":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-languageserver":{"dependencies":{"vscode-languageserver-protocol":"3.17.3"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"vscode-uri":{"dependencies":{},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}},"@prisma/language-server":{"dependencies":{"klona":"2.0.6","vscode-uri":"^3.0.8","@prisma/config":"7.9.0-dev.4","js-levenshtein":"1.1.6","@types/js-levenshtein":"1.1.3","vscode-languageserver":"8.1.0","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","@prisma/schema-files-loader":"7.9.0-dev.4","vscode-languageserver-textdocument":"1.0.11"},"optionalDependencies":{},"peerDependencies":{},"peerDependenciesMeta":{}}},"engineConstraints":{"@babel/helper-string-parser":">=6.9.0","@babel/helper-validator-identifier":">=6.9.0","@babel/parser":">=6.0.0","@babel/types":">=6.9.0","chokidar":">= 20.19.0","deepmerge-ts":">=16.0.0","dotenv":">=12","empathic":">=14","fast-check":">=8.0.0","fs-extra":">=14.14","js-levenshtein":">=0.10.0","klona":">= 8","readdirp":">= 20.19.0","source-map-js":">=0.10.0","universalify":">= 10.0.0","vscode-jsonrpc":">=14.0.0","@prisma/language-server":">=20"}}"""
    val contract: JsonObject by lazy { Json.parseToJsonElement(closureJson).jsonObject }
    val dependencies: Map<String, String> by lazy { contract.getValue("additionalDependencies").jsonObject.mapValues { it.value.jsonPrimitive.content } }
    val rootDependencies: JsonObject get() = contract.getValue("rootDependencies").jsonObject
    val engineConstraints: List<String> get() = contract.getValue("engineConstraints").jsonObject.values.map { it.jsonPrimitive.content }.distinct()

    fun supports(recipe: WorkstationInstallRecipe): Boolean = recipe.familyId == FAMILY_ID && recipe.version == VERSION &&
        recipe.packageName == PACKAGE && recipe.packageVersion == VERSION && recipe.command == COMMAND &&
        recipe.minimumNodeMajor == 22 && recipe.provenanceUrl == PROVENANCE && recipe.registryIntegrity == INTEGRITY &&
        !recipe.allowLifecycleScripts && recipe.lifecycleScriptReview == null && recipe.additionalDependencies == dependencies

    fun validateMetadata(metadata: JsonObject) {
        require(metadata["name"] == JsonPrimitive(PACKAGE) && metadata["version"] == JsonPrimitive(VERSION))
        require(metadata["dist"]?.jsonObject?.get("tarball") == JsonPrimitive(TARBALL) &&
            metadata["dist"]?.jsonObject?.get("integrity") == JsonPrimitive(INTEGRITY)) { "Reviewed Prisma artifact changed" }
        validatePackageMetadata(PACKAGE, metadata)
    }

    fun validateLock(text: String, recipe: WorkstationInstallRecipe): JsonObject {
        require(supports(recipe)) { "Prisma recipe differs from its exact reviewed identity" }
        val lock = strictObject(text)
        require(lock["lockfileVersion"] == JsonPrimitive(3)) { "Prisma requires lockfileVersion 3" }
        require(lock["name"] == JsonPrimitive("droide-managed-agent") && lock["version"] == JsonPrimitive("1.0.0"))
        val entries = lock["packages"]?.jsonObject ?: error("Prisma package lock is missing")
        require(entries.keys == contract.getValue("expectedLockPaths").jsonArray.map { it.jsonPrimitive.content }.toSet()) { "Prisma requires the exact flat 44-package closure" }
        val root = entries.getValue("").jsonObject
        require(root["name"] == JsonPrimitive("droide-managed-agent") && root["version"] == JsonPrimitive("1.0.0") && root["dependencies"] == rootDependencies)
        require(listOf("scripts", "optionalDependencies", "peerDependencies", "overrides", "workspaces", "resolutions").none { it in root })
        for ((name, expectedRaw) in contract.getValue("artifacts").jsonObject) {
            val actual = entries.getValue("node_modules/$name").jsonObject
            for ((key, expected) in expectedRaw.jsonObject) require(actual[key] == expected) { "Prisma artifact $name $key changed" }
            for (flag in listOf("link", "hasInstallScript", "dev", "inBundle")) require(actual[flag] == null || actual[flag] == JsonPrimitive(false)) { "Prisma dependency flag $flag is not admitted" }
            validateEdges(name, actual)
        }
        return lock
    }

    private fun validateEdges(name: String, actual: JsonObject) {
        val edges = contract.getValue("packageEdges").jsonObject.getValue(name).jsonObject
        for ((key, expected) in edges) require((actual[key] ?: JsonObject(emptyMap())) == expected) { "Prisma dependency edge changed: $name $key" }
        val engine = contract.getValue("engineConstraints").jsonObject[name]
        require(actual["engines"]?.jsonObject?.get("node") == engine) { "Prisma Node engine changed: $name" }
    }

    private fun validatePackageMetadata(name: String, actual: JsonObject) {
        require(actual["name"] == JsonPrimitive(name) && actual["version"] == contract.getValue("artifacts").jsonObject.getValue(name).jsonObject["version"])
        validateEdges(name, actual)
        val scripts = actual["scripts"]?.jsonObject.orEmpty()
        require(listOf("preinstall", "install", "postinstall").none { it in scripts }) { "Unexpected Prisma install lifecycle" }
        if (name == PACKAGE) {
            require(actual["main"] == JsonPrimitive("./dist/index.js"))
            require(actual["exports"] == Json.parseToJsonElement("""{".":{"types":"./dist/index.d.ts","default":"./dist/index.js"},"./prisma-next":{"types":"./dist/lib/prismaNext.d.ts","default":"./dist/lib/prismaNext.js"}}""")) { "Prisma public export changed" }
        }
    }

    /** No dependency directories can be links; only inventoried npm .bin links are later seal-admitted. */
    fun validateInstalledLayout(root: File) {
        val npm = File(root, "payload/npm")
        require(strictObject(readRegular(File(npm, "package.json"), 64_000)) == contract.getValue("rootManifest"))
        val modules = File(npm, "node_modules")
        requireRealDirectory(modules)
        val actual = mutableSetOf<String>()
        for (entry in requireNotNull(modules.listFiles())) {
            if (entry.name == ".package-lock.json") { readRegular(entry, 8_000_000); continue }
            if (entry.name == ".bin") {
                requireRealDirectory(entry)
                for (link in requireNotNull(entry.listFiles())) {
                    if (Files.isSymbolicLink(link.toPath())) {
                        val target = Files.readSymbolicLink(link.toPath())
                        require(!target.isAbsolute && target.toString().length <= 4096 && link.toPath().toRealPath().startsWith(root.canonicalFile.toPath()))
                    } else require(Files.isRegularFile(link.toPath(), NOFOLLOW_LINKS))
                }
                continue
            }
            if (entry.name.startsWith("@")) {
                requireRealDirectory(entry)
                for (member in requireNotNull(entry.listFiles())) { requireRealDirectory(member); actual += "${entry.name}/${member.name}" }
            } else { requireRealDirectory(entry); actual += entry.name }
        }
        val artifacts = contract.getValue("artifacts").jsonObject
        require(actual == artifacts.keys) { "Prisma installed package set changed" }
        var layoutEntries = 0
        var layoutBytes = 0L
        for (name in artifacts.keys) {
            val dir = File(modules, name)
            requireRealDirectory(dir)
            validatePackageMetadata(name, strictObject(readRegular(File(dir, "package.json"), 1_000_000)))
            // An unexpected nested node_modules is never accepted, even if absent from a malicious lock.
            Files.walk(dir.toPath()).use { paths ->
                val it = paths.iterator()
                while (it.hasNext()) {
                    val path = it.next(); require(++layoutEntries <= 40_000 && dir.toPath().relativize(path).nameCount <= 64)
                    require(path == dir.toPath() || path.fileName.toString() != "node_modules") { "Nested Prisma dependencies are not reviewed" }
                    if (Files.isSymbolicLink(path)) {
                        val target = Files.readSymbolicLink(path)
                        require(!target.isAbsolute && target.toString().length <= 4096 && path.toRealPath().startsWith(root.canonicalFile.toPath())) { "Prisma link escaped its package" }
                    } else if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        layoutBytes += Files.size(path); require(layoutBytes <= 1_500_000_000L)
                    } else require(Files.isDirectory(path, NOFOLLOW_LINKS))
                }
            }
        }
        readRegular(File(modules, "$PACKAGE/dist/index.js"), 8_000_000)
    }

    fun requireRealDirectory(file: File) {
        require(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) { "Prisma directory is missing or linked" }
        var parent = file.absoluteFile.parentFile
        while (parent != null) { require(!Files.isSymbolicLink(parent.toPath())); parent = parent.parentFile }
    }

    fun readRegular(file: File, limit: Int): String {
        requireRealDirectory(requireNotNull(file.absoluteFile.parentFile))
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..limit.toLong()) { "Prisma key file is missing, linked or oversized" }
        val bytes = file.readBytes()
        require(bytes.size in 1..limit)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Validate lexical structure before the standard parser, which otherwise discards duplicate keys. */
    fun strictObject(text: String, maxBytes: Int = MAX_JSON_BYTES): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size in 1..maxBytes)
        StrictJson(text).validate()
        return Json.parseToJsonElement(text) as? JsonObject ?: error("Prisma JSON must be an object")
    }
    private class StrictJson(private val s: String) {
        private var i = 0
        private var values = 0
        fun validate() { value(0); white(); require(i == s.length) }
        private fun white() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun take(c: Char): Boolean { white(); return if (i < s.length && s[i] == c) { i++; true } else false }
        private fun string(): String {
            white(); val start = i; require(i < s.length && s[i++] == '"')
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return Json.parseToJsonElement(s.substring(start, i)).jsonPrimitive.content
                require(c.code >= 32)
                if (c == '\\') { require(i < s.length); val e = s[i++]; require(e in "\"\\/bfnrtu"); if (e == 'u') { require(i + 4 <= s.length && s.substring(i, i + 4).all { it in "0123456789abcdefABCDEF" }); i += 4 } }
            }
            error("Unterminated Prisma JSON string")
        }
        private fun value(depth: Int) {
            require(depth <= 64 && ++values <= 100_000); white(); require(i < s.length)
            when (s[i]) {
                '{' -> { i++; val keys = hashSetOf<String>(); if (!take('}')) do { require(keys.add(string())) { "Duplicate Prisma JSON key" }; require(take(':')); value(depth + 1); if (take('}')) break; require(take(',')) } while (true) }
                '[' -> { i++; if (!take(']')) do { value(depth + 1); if (take(']')) break; require(take(',')) } while (true) }
                '"' -> string()
                else -> { val start = i; while (i < s.length && s[i] !in ",]} \t\r\n") i++; require(i > start); val token = s.substring(start, i); require(token in setOf("true", "false", "null") || token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) }
            }
        }
    }
}

# Third-party notices

## chroma

The syntax-highlighting grammars under `resources/highlight/grammars/` are
ported from [chroma](https://github.com/alecthomas/chroma) by
`scripts/convert-chroma-grammars.mjs`. chroma is MIT licensed:

```
Copyright (C) 2017 Alec Thomas

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## tree-sitter runtime and grammars

`resources/treesitter/` holds the [web-tree-sitter](https://github.com/tree-sitter/tree-sitter)
runtime and one grammar per language, compiled to WebAssembly by
`scripts/build-treesitter-wasm.mjs` from the revisions pinned in
`resources/treesitter/manifest.json`.

| Component | License | Copyright |
| --- | --- | --- |
| web-tree-sitter (runtime) | MIT | 2018 Max Brunsfeld |
| tree-sitter-bash | MIT | 2017 Max Brunsfeld |
| tree-sitter-css | MIT | 2018 Max Brunsfeld |
| tree-sitter-go | MIT | 2014 Max Brunsfeld |
| tree-sitter-javascript | MIT | 2014 Max Brunsfeld |
| tree-sitter-python | MIT | 2016 Max Brunsfeld |
| tree-sitter-typescript (typescript, tsx) | MIT | 2017 Max Brunsfeld |
| tree-sitter-rust | MIT | 2017 Maxim Sokolov |
| tree-sitter-nix | MIT | 2019 Charles Strahan |
| tree-sitter-clojure | CC0 1.0 Universal (public domain dedication) | — |

The MIT-licensed components are each distributed under these terms, with the
copyright holder named in the table:

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The runtime's own license file ships alongside it as
`resources/treesitter/web-tree-sitter.LICENSE`.

/* xi-treesitter — parse a source file with a tree-sitter grammar .so and
 * print the parse tree as compact JSON on stdout.
 *
 * Usage: xi-treesitter <grammar-dir> <lang> <file>
 *
 * Loads <grammar-dir>/<lang>.so (a nix tree-sitter grammar, e.g. from
 * `tree-sitter.withPlugins`), resolves `tree_sitter_<lang>`, parses <file>,
 * and emits one JSON object per node:
 *
 *   {"t":"<type>","sr":0,"er":3,"sb":0,"eb":120,"f":"name","a":1,"c":[…]}
 *
 *   t  node type            sr/er  start/end row (0-based)
 *   sb/eb byte range        f      field name (when present)
 *   a  1 when anonymous     c      children (all, named + anonymous)
 *
 * The consumer slices node text out of the source via sb/eb, so no text is
 * duplicated into the JSON.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <tree_sitter/api.h>

static void json_escape(const char *s, FILE *out) {
  for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
    switch (*p) {
      case '"': fputs("\\\"", out); break;
      case '\\': fputs("\\\\", out); break;
      case '\n': fputs("\\n", out); break;
      case '\r': fputs("\\r", out); break;
      case '\t': fputs("\\t", out); break;
      default:
        if (*p < 0x20) {
          fprintf(out, "\\u%04x", *p);
        } else {
          fputc(*p, out);
        }
    }
  }
}

static void print_node(TSTreeCursor *cursor, FILE *out) {
  TSNode node = ts_tree_cursor_current_node(cursor);
  const char *field = ts_tree_cursor_current_field_name(cursor);
  TSPoint sp = ts_node_start_point(node);
  TSPoint ep = ts_node_end_point(node);

  fputs("{\"t\":\"", out);
  json_escape(ts_node_type(node), out);
  fprintf(out, "\",\"sr\":%u,\"er\":%u,\"sb\":%u,\"eb\":%u",
          sp.row, ep.row, ts_node_start_byte(node), ts_node_end_byte(node));
  if (!ts_node_is_named(node)) fputs(",\"a\":1", out);
  if (field) {
    fputs(",\"f\":\"", out);
    json_escape(field, out);
    fputc('"', out);
  }
  if (ts_tree_cursor_goto_first_child(cursor)) {
    fputs(",\"c\":[", out);
    int first = 1;
    do {
      if (!first) fputc(',', out);
      first = 0;
      print_node(cursor, out);
    } while (ts_tree_cursor_goto_next_sibling(cursor));
    ts_tree_cursor_goto_parent(cursor);
    fputc(']', out);
  }
  fputc('}', out);
}

static char *read_file(const char *path, long *len_out) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *buf = malloc(len + 1);
  if (!buf) { fclose(f); return NULL; }
  if (fread(buf, 1, len, f) != (size_t)len) { fclose(f); free(buf); return NULL; }
  fclose(f);
  buf[len] = '\0';
  *len_out = len;
  return buf;
}

int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: xi-treesitter <grammar-dir> <lang> <file>\n");
    return 2;
  }
  const char *grammar_dir = argv[1];
  const char *lang = argv[2];
  const char *file = argv[3];

  char so_path[4096];
  snprintf(so_path, sizeof(so_path), "%s/%s.so", grammar_dir, lang);

  void *handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
  if (!handle) {
    fprintf(stderr, "error: cannot load grammar %s: %s\n", so_path, dlerror());
    return 3;
  }

  char sym[256];
  snprintf(sym, sizeof(sym), "tree_sitter_%s", lang);
  TSLanguage *(*lang_fn)(void) = (TSLanguage * (*)(void)) dlsym(handle, sym);
  if (!lang_fn) {
    fprintf(stderr, "error: grammar %s has no symbol %s\n", so_path, sym);
    return 3;
  }

  long len = 0;
  char *source = read_file(file, &len);
  if (!source) {
    fprintf(stderr, "error: cannot read file %s\n", file);
    return 4;
  }

  TSParser *parser = ts_parser_new();
  if (!ts_parser_set_language(parser, lang_fn())) {
    fprintf(stderr, "error: grammar %s is ABI-incompatible with this tree-sitter\n", so_path);
    return 5;
  }

  TSTree *tree = ts_parser_parse_string(parser, NULL, source, (uint32_t)len);
  if (!tree) {
    fprintf(stderr, "error: parse failed\n");
    return 6;
  }

  static char out_buf[1 << 20];
  setvbuf(stdout, out_buf, _IOFBF, sizeof(out_buf));

  TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
  print_node(&cursor, stdout);
  fputc('\n', stdout);
  fflush(stdout);

  ts_tree_cursor_delete(&cursor);
  ts_tree_delete(tree);
  ts_parser_delete(parser);
  free(source);
  return 0;
}

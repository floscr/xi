(ns xi.highlight.grammar.v-shell)

(def v-shell
  [   {:pattern "^#![^\\n]*\\n" :token :comment}
   {:pattern "\\b(path_delimiter|path_separator|wd_at_startup|max_path_len|sys_write|sys_close|sys_mkdir|sys_creat|sys_open|s_iflnk|s_irusr|s_ifdir|s_ixoth|s_isuid|s_isgid|s_isvtx|s_iwoth|s_iwusr|s_ixusr|s_irgrp|s_iwgrp|s_ixgrp|s_iroth|s_ifmt|args)\\b" :token :name-var}
   {:pattern "\\b(ExecutableNotFoundError|FileNotOpenedError|SizeOfTypeIs0Error|ProcessState|SeekMode|Command|Process|Signal|Result|Uname|File)\\b" :token :name-builtin}
   {:pattern "\\b(find_abs_path_of_executable|posix_set_permission_bit|read_bytes_into_newline|sigint_to_signal_name|exists_in_system_path|get_raw_lines_joined|expand_tilde_to_home|posix_get_error_msg|set_redirect_stdio|is_writable_folder|file_last_mod_unix|walk_with_context|resource_abs_path|debugger_present|write_file_array|join_path_single|execute_or_panic|get_lines_joined|set_environment|read_bytes_into|read_file_array|execute_or_exit|signal_continue|write_struct_at|read_struct_at|vmodules_paths|get_error_msg|signal_pgkill|read_into_ptr|read_bytes_at|existing_path|is_executable|get_raw_stdin|is_dir_empty|write_struct|vmodules_dir|stdout_slurp|write_raw_at|write_string|get_raw_line|stderr_slurp|signal_kill|stderr_read|signal_stop|quoted_path|stdin_write|stdout_read|open_append|new_process|args_before|read_struct|is_writable|is_readable|is_abs_path|read_raw_at|write_file|getenv_opt|args_after|read_lines|read_bytes|signal_opt|config_dir|last_error|executable|file_name|file_size|rmdir_all|write_raw|real_path|join_path|input_opt|norm_path|read_from|get_lines|loginname|read_file|cache_dir|mkdir_all|read_line|open_file|home_dir|hostname|fd_slurp|fd_close|mv_by_cp|open_uri|file_ext|walk_ext|unsetenv|write_to|fd_write|abs_path|read_raw|is_alive|get_line|truncate|temp_dir|set_args|geteuid|is_file|getppid|bitmask|is_atty|execute|symlink|environ|fd_read|is_link|writeln|getegid|user_os|is_dir|stdout|create|fileno|cp_all|system|getenv|vfopen|execve|getgid|stderr|getuid|execvp|exists|setenv|getpid|flush|getwd|input|stdin|mkdir|chdir|chmod|start|chown|rmdir|uname|write|close|utime|inode|wait|seek|base|fork|open|link|glob|read|tell|walk|msg|dir|run|log|cp|mv|rm|ls)(?=\\()" :token :name-builtin}
   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "(?<=module\\s+\\w[^\\n]*\\s+)(//[^\\n]+\\n)+(?=\\n)" :token :string}
   {:pattern "//[^\\n]*\\n" :token :comment}
   {:pattern "/\\*(?:(?:/\\*(?:.|\\n)*?\\*/)*|.|\\n)*\\*/" :token :comment}
   {:pattern "\\b(import|module)\\b" :token :keyword}
   {:pattern "\\b(fn|struct|union|map|chan|type|interface|enum|const|mut|shared|pub|__global)\\b" :token :keyword-decl}
   {:pattern "\\?" :token :keyword-decl}
   {:pattern "(?<=\\)\\s*)!" :token :keyword-decl}
   {:pattern "[ \\t]*#\\w[^\\n]*" :token :comment}
   {:pattern "\\$(?=\\w)" :token :operator}
   {:pattern "(?<=\\$)(?:embed_file|pkgconfig|tmpl|env|compile_error|compile_warn)" :token :name-builtin}
   {:pattern "\\b_(?:un)?likely_(?=\\()" :token :name-fn}
   {:pattern "(?<=\\$if.+?(?:&&|\\|\\|)?)((no_segfault_handler|no_bounds_checking|little_endian|freestanding|no_backtrace|big_endian|cplusplus|dragonfly|prealloc|android|windows|no_main|solaris|darwin|clang|tinyc|glibc|mingw|haiku|macos|amd64|arm64|debug|linux|prod|msvc|test|hpux|mach|x32|x64|gcc|qnx|gnu|ios|mac|js))+" :token :name-builtin}
   {:pattern "@(VMOD_FILE|VEXEROOT|VMODROOT|METHOD|STRUCT|COLUMN|VHASH|FILE|LINE|VEXE|MOD|FN)\\b" :token :name-var}
   {:pattern "\\b(?<!@)(__offsetof|isreftype|continue|volatile|typeof|static|unsafe|return|assert|sizeof|atomic|select|match|break|defer|rlock|lock|else|goto|for|in|is|as|or|if|go)\\b" :token :keyword}
   {:pattern "\\b(?<!@)(none|true|false|si_s_code|si_g32_code|si_g64_code)\\b" :token :keyword}
   {:pattern "\\b(?<!@)(vstring_literal_with_len|utf8_str_visible_length|get_str_intp_u64_format|get_str_intp_u32_format|utf32_decode_to_buffer|utf32_to_str_no_malloc|panic_optional_not_set|panic_result_not_set|contains_any_substr|strip_margin_custom|starts_with_capital|cstring_to_vstring|winapi_lasterr_str|c_error_number_str|panic_error_number|substr_with_check|string_from_wide2|sort_with_compare|trim_string_right|string_from_wide|sort_ignore_case|trim_string_left|reverse_in_place|split_into_lines|vstring_with_len|compare_strings|all_before_last|print_backtrace|repeat_to_depth|length_in_bytes|error_with_code|vstring_literal|gc_check_leaks|clone_to_depth|vcalloc_noscan|all_after_last|utf8_char_len|panic_lasterr|memdup_noscan|malloc_noscan|str_intp_rune|last_index_u8|utf8_to_utf32|flush_stderr|flush_stdout|str_intp_sub|replace_each|replace_once|vstrlen_char|utf8_getchar|str_intp_g64|contains_any|find_between|realloc_data|strip_margin|utf32_to_str|is_bin_digit|is_hex_digit|is_oct_digit|proc_pidpath|str_intp_g32|delete_many|delete_last|str_escaped|index_after|sort_by_len|str_intp_sq|starts_with|trim_space|last_index|parse_uint|is_capital|trim_right|join_lines|capitalize|all_before|after_char|match_glob|utf32_code|eq_epsilon|tos_clone|substr_ni|v_realloc|push_many|is_letter|split_nth|split_any|trim_left|index_any|ascii_str|parse_int|all_after|ends_with|is_title|contains|eprintln|is_space|index_u8|is_digit|vmemmove|byterune|is_alnum|pointers|grow_len|is_lower|grow_cap|str_intp|to_upper|hex_full|len_utf8|is_upper|try_push|to_lower|compare|vmemset|try_pop|vcalloc|reverse|vmemcmp|vstring|replace|ptr_str|strlong|to_wide|bytestr|f32_abs|f32_max|println|f32_min|f64_max|vmemcpy|vstrlen|prepend|vbytes|strsci|substr|string|insert|eprint|filter|delete|repeat|malloc|memdup|fields|before|reduce|string|isize|count|index|title|bytes|clone|usize|error|after|split|runes|print|panic|first|close|limit|isnil|bool|rune|tos3|u128|hex2|i128|hash|code|tos4|free|exit|keys|tos2|last|trim|strg|tos5|move|copy|drop|sort|dump|join|free|cap|int|any|all|any|len|map|pop|hex|f64|f32|u16|msg|i64|i16|u32|str|tos|u64|i8|u8)(?=\\()" :token :name-builtin}
   {:pattern "\\b(?<!@)(StrIntpCgenData|VAssertMetaInfo|StructAttribute|AttributeKind|FunctionData|StrIntpData|StrIntpType|MethodArgs|StrIntpMem|ArrayFlags|FieldData|SortedMap|ChanState|string|array|Error|map)\\b" :token :name-builtin}
   {:pattern "\\b(?<!@)(voidptr|string|error|isize|usize|i128|u128|bool|rune|int|f32|f64|i64|i16|u64|any|u32|u16|i8|u8)\\b" :token :keyword-type}
   {:pattern "\\bit\\b" :token :name-var}
   {:pattern "(<<=|>>=|>>>=|>>>|<<|>>|<=|>=|\\^=|\\+=|-=|\\*=|/=|%=|&=|\\|=|&&|\\|\\||<-|\\+\\+|--|==|!=|:=|\\.\\.\\.|\\.\\.|[+\\-*/%&|^~=#@!])" :token :operator}
   {:pattern "[\\d_]+(\\.\\d+e[+\\-]?\\d+|\\.\\d+|e[+\\-]?\\d+)" :token :number}
   {:pattern "\\.\\d+(e[+\\-]?\\d+)?" :token :number}
   {:pattern "0o[0-7_]+" :token :number}
   {:pattern "0x[0-9a-fA-F_]+" :token :number}
   {:pattern "0b[01_]+" :token :number}
   {:pattern "(0|[1-9][0-9_]*)" :token :number}
   {:pattern "@?[A-Z]\\w*" :token :name-class}
   {:pattern "@?[^\\W\\d]\\w*(?=\\()" :token :name-fn}
   {:pattern "(?<=fn\\s+)@?[^\\W\\d]\\w*(?=\\s*\\()" :token :name-fn}
   {:pattern "(?<=(?:continue|break|goto)\\s+)\\w+" :token :name-var}
   {:pattern "\\b[^\\W\\d]\\w*(?=:(?:$|\\s+for))" :token :name-var}
   {:pattern "[<>()\\[\\]{}.,;:]" :token :punctuation}
   {:pattern "@?[^\\W\\d]\\w*" :token :name-var}])

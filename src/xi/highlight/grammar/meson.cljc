(ns xi.highlight.grammar.meson)

(def meson
  [   {:pattern "#.*?$" :token :comment}
   {:pattern "'''.*'''" :token :string}
   {:pattern "[1-9][0-9]*" :token :number}
   {:pattern "0o[0-7]+" :token :number}
   {:pattern "0x[a-fA-F0-9]+" :token :number}
   {:pattern "[a-zA-Z_][a-zA-Z_0-9]*" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "[']{3}([']{0,2}([^\\\\']|\\\\(.|\\n)))*[']{3}" :token :string}
   {:pattern "'.*?(?<!\\\\)(\\\\\\\\)*?'" :token :string}
   {:pattern "(endforeach|continue|foreach|break|endif|else|elif|if)\\b" :token :keyword}
   {:pattern "(in|and|or|not)\\b" :token :operator}
   {:pattern "(\\*=|/=|%=|\\+]=|-=|==|!=|\\+|-|=)" :token :operator}
   {:pattern "[\\[\\]{}:().,?]" :token :punctuation}
   {:pattern "(false|true)\\b" :token :keyword}
   {:pattern "(target_machine|build_machine|host_machine|meson)\\b" :token :name-var}
   {:pattern "(?<!\\.)(add_project_link_arguments|add_global_link_arguments|add_project_arguments|add_global_arguments|include_directories|configuration_data|declare_dependency|install_headers|both_libraries|install_subdir|add_test_setup|configure_file|static_library|shared_library|custom_target|add_languages|shared_module|set_variable|get_variable|find_library|find_program|build_target|install_data|environment|is_disabler|run_command|subdir_done|install_man|is_variable|subproject|dependency|join_paths|get_option|executable|generator|benchmark|disabler|project|message|library|summary|vcs_tag|warning|assert|subdir|range|files|error|test|jar)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)import\\b" :token :name-var}])

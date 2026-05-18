(ns xi.highlight.grammar.fish)

(def fish
  [   {:pattern "(?<=(?:^|\\A|;|&&|\\|\\||\\||\\b(continue|function|return|switch|begin|while|break|count|false|block|echo|case|true|else|exit|test|set|cdh|and|pwd|for|end|not|if|cd|or)\\b)\\s*)(continue|function|return|switch|begin|while|break|count|false|block|test|case|true|echo|exit|else|set|cdh|and|pwd|for|end|not|if|cd|or)(?=;?\\b)" :token :keyword}
   {:pattern "(?<=for\\s+\\S+\\s+)in\\b" :token :keyword}
   {:pattern "\\b(fish_update_completions|fish_command_not_found|fish_breakpoint_prompt|fish_status_to_signal|fish_right_prompt|fish_is_root_user|fish_mode_prompt|fish_vcs_prompt|fish_key_reader|fish_svn_prompt|fish_git_prompt|fish_hg_prompt|fish_greeting|fish_add_path|commandline|fish_prompt|fish_indent|fish_config|fish_pager|breakpoint|fish_title|prompt_pwd|functions|set_color|realpath|funcsave|contains|complete|argparse|fish_opt|history|builtin|getopts|suspend|command|mimedb|printf|ulimit|disown|string|source|funced|status|random|isatty|fishd|prevd|vared|umask|nextd|alias|pushd|emit|jobs|popd|help|psub|wait|fish|read|time|exec|eval|math|trap|type|dirs|dirh|abbr|kill|bind|hash|open|fc|bg|fg)\\s*\\b(?!\\.)" :token :name-builtin}
   {:pattern "#!.*\\n" :token :comment}
   {:pattern "#.*\\n" :token :comment}
   {:pattern "\\\\[\\w\\W]" :token :string}
   {:pattern "[\\[\\]()={}]" :token :operator}
   {:pattern "(?<=\\[[^\\]]+)\\.\\.|-(?=[^\\[]+\\])" :token :operator}
   {:pattern "<<-?\\s*(\\'?)\\\\?(\\w+)[\\w\\W]+?\\2" :token :string}
   {:pattern "(?<=set\\s+(?:--?[^\\d\\W][\\w-]*\\s+)?)\\w+" :token :name-var}
   {:pattern "(?<=for\\s+)\\w[\\w-]*(?=\\s+in)" :token :name-var}
   {:pattern "(?<=function\\s+)\\w(?:[^\\n])*?(?= *[-\\n])" :token :name-fn}
   {:pattern "(?<=(?:^|\\b(?:and|or|sudo)\\b|;|\\|\\||&&|\\||\\(|(?:\\b\\w+\\s*=\\S+\\s)) *)\\w[\\w-]*" :token :name-fn}
   {:pattern "\\$#?(\\w+|.)" :token :name-var}
   {:pattern ";" :token :punctuation}
   {:pattern "&&|\\|\\||&|\\||\\^|<|>" :token :operator}
   {:pattern "\\s+" :token :text}
   {:pattern "\\b\\d+\\b" :token :number}
   {:pattern "(?<=\\s+)--?[^\\d][\\w-]*" :token :name-var}
   {:pattern ".+?" :token :text}])

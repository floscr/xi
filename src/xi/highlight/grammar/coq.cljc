(ns xi.highlight.grammar.coq)

(def coq
  [   {:pattern "\\s+" :token :text}
   {:pattern "false|true|\\(\\)|\\[\\]" :token :name-builtin}
   {:pattern "\\b(Projections|Monomorphic|Polymorphic|Proposition|CoInductive|Hypothesis|CoFixpoint|Contextual|Definition|Parameters|Hypotheses|Structure|Inductive|Corollary|Implicits|Parameter|Variables|Arguments|Canonical|Printing|Coercion|Reserved|Universe|Notation|Instance|Fixpoint|Variable|Morphism|Relation|Existing|Implicit|Example|Theorem|Delimit|Defined|Rewrite|outside|Require|Resolve|Section|Context|Prenex|Strict|Module|Import|Export|Global|inside|Remark|Tactic|Search|Record|Scope|Unset|Check|Local|Close|Class|Graph|Proof|Lemma|Print|Axiom|Show|Goal|Open|Fact|Hint|Bind|Ltac|Save|View|Let|Set|All|End|Qed)\\b" :token :keyword}
   {:pattern "\\b(exists2|nosimpl|struct|exists|return|forall|match|cofix|then|with|else|for|fix|let|fun|end|is|of|if|in|as)\\b" :token :keyword}
   {:pattern "\\b(Type|Prop)\\b" :token :keyword-type}
   {:pattern "\\b(native_compute|setoid_rewrite|etransitivity|econstructor|transitivity|autorewrite|constructor|cutrewrite|vm_compute|bool_congr|generalize|inversion|induction|injection|nat_congr|intuition|destruct|suffices|erewrite|symmetry|nat_norm|replace|rewrite|compute|pattern|trivial|without|assert|unfold|change|eapply|intros|unlock|revert|rename|refine|eauto|tauto|after|right|congr|split|field|simpl|intro|clear|apply|using|subst|case|left|suff|loss|wlog|have|fold|ring|move|lazy|elim|pose|auto|red|cbv|hnf|cut|set)\\b" :token :keyword}
   {:pattern "\\b(contradiction|discriminate|reflexivity|assumption|congruence|romega|omega|exact|solve|tauto|done|by)\\b" :token :keyword}
   {:pattern "\\b(repeat|first|idtac|last|try|do)\\b" :token :keyword}
   {:pattern "\\b([A-Z][\\w\\']*)" :token :text}
   {:pattern "(λ|Π|\\|\\}|\\{\\||\\\\/|/\\\\|=>|~|\\}|\\|]|\\||\\{<|\\{|`|_|]|\\[\\||\\[>|\\[<|\\[|\\?\\?|\\?|>\\}|>]|>|=|<->|<-|<|;;|;|:>|:=|::|:|\\.\\.|\\.|->|-\\.|-|,|\\+|\\*|\\)|\\(|&&|&|#|!=)" :token :operator}
   {:pattern "([=<>@^|&+\\*/$%-]|[!?~])?[!$%&*+\\./:<=>?@^|~-]" :token :operator}
   {:pattern "\\b(unit|nat|bool|string|ascii|list)\\b" :token :keyword-type}
   {:pattern "[^\\W\\d][\\w']*" :token :text}
   {:pattern "\\d[\\d_]*" :token :number}
   {:pattern "0[xX][\\da-fA-F][\\da-fA-F_]*" :token :number}
   {:pattern "0[oO][0-7][0-7_]*" :token :number}
   {:pattern "0[bB][01][01_]*" :token :number}
   {:pattern "-?\\d[\\d_]*(.[\\d_]*)?([eE][+\\-]?\\d[\\d_]*)" :token :number}
   {:pattern "'(?:(\\\\[\\\\\\\"'ntbr ])|(\\\\[0-9]{3})|(\\\\x[0-9a-fA-F]{2}))'" :token :string-char}
   {:pattern "'.'" :token :string-char}
   {:pattern "'" :token :keyword}
   {:pattern "[~?][a-z][\\w\\']*:" :token :text}])

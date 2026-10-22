(ns xi.providers.routing-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.routing :as routing]))

;; The routes the bundled providers' manifests declare (xi-providers).
(def ^:private specs
  [{:id :anthropic :label "Claude" :side-turns? true
    :routes {:prefixes ["claude-" "anthropic/"] :ids ["sonnet" "opus" "haiku"]}}
   {:id :ollama :label "Ollama" :routes {:prefixes [] :ids [] :default? true}}
   {:id :openai :label "OpenAI Codex" :routes {:prefixes ["openai/"] :ids []}}
   {:id :zen :label "OpenCode" :routes {:prefixes ["opencode/" "opencode-go/"] :ids []}}])

(deftest provider-for-model
  (testing "a matching prefix wins over an overlapping claude bare id"
    (is (= :zen (routing/provider-for-model specs "opencode/claude-opus-4-8")))
    (is (= :zen (routing/provider-for-model specs "opencode-go/deepseek-v4.1-flash"))))
  (testing "prefixes and exact ids"
    (is (= :openai (routing/provider-for-model specs "openai/gpt-5.1-codex")))
    (is (= :anthropic (routing/provider-for-model specs "claude-opus-4-6")))
    (is (= :anthropic (routing/provider-for-model specs "anthropic/claude-sonnet-4")))
    (is (= :anthropic (routing/provider-for-model specs "sonnet"))))
  (testing "everything else goes to the default provider"
    (is (= :ollama (routing/provider-for-model specs "llama3")))
    (is (= :ollama (routing/provider-for-model specs "gpt-5.1-codex")))
    (is (= :ollama (routing/provider-for-model specs nil))))
  (testing "without a default nothing routes"
    (is (nil? (routing/provider-for-model (remove :default? (map #(update % :routes dissoc :default?) specs))
                                          "llama3")))
    (is (nil? (routing/provider-for-model [] "claude-opus-4-6")))))

(deftest side-turns-and-specs
  (is (= :anthropic (routing/side-turn-id specs)))
  (is (= "Claude" (:label (routing/agent-spec specs {:model "haiku"}))))
  (is (= "OpenCode" (:label (routing/agent-spec specs {:provider :zen :model "claude-x"}))))
  (is (= :b (:id (routing/side-turn-provider (array-map :a {:id :a} :b {:id :b :side-turns? true}))))))

(deftest models-come-from-the-providers
  (let [providers (array-map :ollama {:id :ollama}
                             :anthropic {:id :anthropic :side-turns? true :default-model "claude-x"
                                         :side-models {:fast "claude-small" :strong "claude-mid"}})]
    (is (= "claude-x" (routing/default-model providers)))
    (is (nil? (routing/default-model {:ollama {:id :ollama}})) "no provider names one")
    (is (= "claude-small" (routing/side-model (routing/side-turn-provider providers) :fast)))
    (is (= "claude-mid" (routing/side-model (routing/side-turn-provider providers) :strong)))))

(deftest providers-changed
  (let [h (:providers/changed routing/handlers)]
    (is (= specs (:providers (:state (h {} {:providers specs})))))
    (is (nil? (h {:providers specs} {:providers specs})) "an unchanged table is no change")))

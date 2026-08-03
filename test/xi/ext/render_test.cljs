(ns xi.ext.render-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.manager :as manager]
            [xi.ext.mcp :as mcp]
            [xi.ext.render :as render]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(deftest registry-entry-is-a-disabled-http-server
  (let [e (render/registry-entry)]
    (testing "connects over native HTTP to the hosted Render MCP server"
      (is (= :http (:transport e)))
      (is (= "https://mcp.render.com/mcp" (:url e))))
    (testing "defaults to disabled so deploy/env tools never load unopted"
      (is (= false (:enabled e))))
    (testing "carries an :auth descriptor, never a literal key"
      (is (= {:ext-config "render" :key "RENDER_API_KEY"
              :header "Authorization" :scheme "Bearer"}
             (:auth e)))
      (is (not (contains? e :headers))
          "no literal Authorization header is baked into the entry"))))

(deftest create-returns-nil-without-a-manager
  (is (nil? (render/create {})))
  (is (nil? (render/create {:manager nil}))))

(deftest create-seeds-a-disabled-entry-and-exposes-render-command
  ;; redirect os/homedir to a throwaway dir so we exercise the real seeding
  ;; I/O (write ~/.config/xi/mcp.edn) without touching the user's config
  (let [saved-home (aget js/process.env "HOME")
        tmp        (path/join (os/tmpdir)
                              (str "xi-render-test-" (js/Date.now)))]
    (aset js/process.env "HOME" tmp)
    (try
      (let [mgr (manager/create)
            ext (render/create {:manager mgr})]
        (testing "returns an extension carrying the /render command"
          (is (= :render (:id ext)))
          (is (= "render" (-> ext :commands first :name))))
        (testing "seeds a disabled :render http entry into mcp.edn"
          (let [reg (mcp/read-registry)]
            (is (= :http (get-in reg [:render :transport])))
            (is (= false (get-in reg [:render :enabled])))
            (is (= "https://mcp.render.com/mcp" (get-in reg [:render :url])))))
        (testing "seeding is idempotent — a second create does not overwrite"
          (let [f (path/join tmp ".config" "xi" "mcp.edn")]
            ;; user disables/edits are respected: flip a marker, re-create,
            ;; and confirm it was left untouched
            (fs/writeFileSync f (pr-str {:render {:transport :http :url "x"
                                                  :enabled false :marker 1}}))
            (render/create {:manager (manager/create)})
            (is (= 1 (get-in (mcp/read-registry) [:render :marker]))))))
      (finally
        (if saved-home (aset js/process.env "HOME" saved-home)
            (js-delete js/process.env "HOME"))
        (try (fs/rmSync tmp #js {:recursive true :force true})
             (catch :default _ nil))))))

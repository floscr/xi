(ns xi.api.usage
  "Usage readings for user extensions' `:usage-sources`: the parsers
   xi.server.usage applies to the built-in accounts, so an extension holding
   another login's payload (an account pool) renders the same card. Pure: no
   capability, no rules request."
  (:require [xi.usage :as usage]))

(defn claude-reading
  "(claude-reading {:response m :email s :plan s :tier s :expires-at ms :now ms})
   → the card of a Claude login from its /api/oauth/usage payload (keyword
   keys), nil when it carries no usage. `plan` and `tier` are the credentials'
   subscriptionType and rateLimitTier; `expires-at` the token's expiry."
  [opts]
  (usage/claude-reading opts))

(defn codex-reading
  "(codex-reading {:response m :now ms}) → the card of a Codex login from
   chatgpt.com's wham/usage payload, nil without rate limits."
  [opts]
  (usage/codex-reading opts))

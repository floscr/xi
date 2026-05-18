(ns xi.highlight.grammar.docker)

(def docker
  [   {:pattern "#.*" :token :comment}
   {:pattern "((?:RUN|CMD|ENTRYPOINT|ENV|ARG|LABEL|ADD|COPY))" :token :keyword}])

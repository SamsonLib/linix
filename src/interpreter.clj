(ns interpreter
  (:require [builder :as builder]
            [errors :as errors]
            [modules :as modules]
            [params :as params]
            [reader :as reader]
            [user-function :refer [->UserFunction]])
  (:import (user_function UserFunction)))

;; Errors
;;
;; Every error below is raised through errors/fail, which tags the ex-data with
;; a :type so callers can branch on it instead of matching on the message text.
;; The `loc` argument is the {:file :line :col} of the form being evaluated; it
;; is threaded down from the top-level form that read-all located, and becomes
;; the prefix of the error message. Nested forms inherit the position of the
;; top-level form enclosing them, so an error inside a multi-line defn points
;; at the line the defn starts on -- the same thing Clojure's own compiler
;; does.

(defn- assert-built!
  [ctx]
  (when-not (builder/built? ctx)
    (errors/fail :unbuilt-context
                 "Context has not been built yet — call builder/build on it first."
                 {})))

(defn- malformed
  "Report a special form that was given the wrong shape."
  [loc op expected actual]
  (errors/fail :malformed-form
               (str op " expects " expected " — got " (pr-str actual))
               (assoc loc :op op :expected expected :actual actual)))

(defn- check-params!
  "Report the first entry of a parameter list that is not a symbol or a
   destructuring map. `&` is allowed through for variadic functions."
  [loc op fn-params]
  (when-let [bad (first (remove params/valid-param? (remove #{'&} fn-params)))]
    (errors/fail :invalid-params
                 (str op " parameter " (pr-str bad)
                      " must be a symbol or a {:keys [...]} map")
                 (assoc loc :op op :param bad :params fn-params))))

(defn resolve-symbol
  ([ctx env symbol] (resolve-symbol ctx env symbol nil))
  ([ctx env symbol loc]
   (cond
     (contains? env symbol)
     (get env symbol)

     (contains? @(:variables ctx) symbol)
     (get @(:variables ctx) symbol)

     (contains? (:builtins ctx) symbol)
     (get (:builtins ctx) symbol)

     :else
     (errors/fail :unknown-symbol
                  (str "Unknown symbol: " symbol)
                  (assoc loc :symbol symbol)))))

(declare eval-form)

(defn- eval-body
  "Evaluate a sequence of located forms in order, returning the last value.

   Elements are the Located records that read-all produces for top-level forms
   and reader/located produces for nested ones, so every form carries the
   position errors should report."
  [ctx env body]
  (reduce
   (fn [_ located]
     (eval-form ctx env (:form located) (:loc located)))
   nil
   body))

(defn eval-list
  ([ctx env form] (eval-list ctx env form nil))
  ([ctx env form loc]
   (let [op   (first form)
         args (rest form)]
     (case op

       def
       (let [[name value] args]
         (when-not (symbol? name)
           (malformed loc 'def "a symbol to bind, e.g. (def x 1)" name))
         (let [value (eval-form ctx env value loc)]
           (swap! (:variables ctx) assoc name value)
           value))

       defn
       (let [[name & rest-args] args
             docstring?         (string? (first rest-args))
             fn-params          (if docstring? (second rest-args) (first rest-args))
             body               (if docstring? (drop 2 rest-args) (rest rest-args))]
         (when-not (symbol? name)
           (malformed loc 'defn "a symbol to bind, e.g. (defn f [x] x)" name))
         (when-not (vector? fn-params)
           (malformed loc 'defn "a params vector, e.g. (defn f [x] x)" fn-params))
         (check-params! loc 'defn fn-params)
         (let [function (->UserFunction fn-params (reader/located loc body) env)]
           (swap! (:variables ctx) assoc name function)
           function))

       fn
       (let [[fn-params & body] args]
         (when-not (vector? fn-params)
           (malformed loc 'fn "a params vector, e.g. (fn [x] x)" fn-params))
         (check-params! loc 'fn fn-params)
         (->UserFunction fn-params (reader/located loc body) env))

       let
       (let [[bindings & body] args]
         (when-not (vector? bindings)
           (malformed loc 'let "a bindings vector, e.g. (let [x 1] x)" bindings))
         ;; Without this an odd count would silently bind the last form to nil
         ;; and then fail on the missing value with a misleading error.
         (when (odd? (count bindings))
           (malformed loc 'let
                      "an even number of binding forms, e.g. (let [x 1] x)"
                      bindings))
         (let [local-env (reduce
                          (fn [e [k v]]
                            (when-not (symbol? k)
                              (errors/fail :malformed-form
                                           (str "let binding names must be symbols, e.g. "
                                                "(let [x 1] x) — got " (pr-str k))
                                           (assoc loc :op 'let :binding k)))
                            (assoc e k (eval-form ctx e v loc)))
                          env
                          (partition 2 bindings))]
           (eval-body ctx local-env (reader/located loc body))))

       do
       (eval-body ctx env (reader/located loc args))

       if
       (let [[condition then else] args]
         (if (eval-form ctx env condition loc)
           (eval-form ctx env then loc)
           (eval-form ctx env else loc)))

       when
       (let [[condition & body] args]
         (when (eval-form ctx env condition loc)
           (eval-body ctx env (reader/located loc body))))

       quote
       (first args)

       require
       (do
         (doseq [module-sym args]
           (when-not (symbol? module-sym)
             (malformed loc 'require "module symbols, e.g. (require tree)" module-sym))
           (modules/require-module! ctx module-sym loc eval-body))
         nil)

       ;; Normal function call
       (let [function       (eval-form ctx env op loc)
             evaluated-args (map #(eval-form ctx env % loc) args)]
         (cond
           (instance? UserFunction function)
           (let [{:keys [params body closure]} function
                 local-env (merge closure (params/bind-params loc params evaluated-args))]
             (eval-body ctx local-env body))

           (ifn? function)
           (apply function evaluated-args)

           :else
           (if (nil? op)
             (errors/fail :empty-call
                          "Cannot evaluate an empty list ()"
                          (assoc loc :form form))
             (errors/fail :not-callable
                          (str "Cannot call " (pr-str op) " — it is not a function")
                          (assoc loc :form form :value function)))))))))

(defn eval-form
  ([ctx env form] (eval-form ctx env form nil))
  ([ctx env form loc]
   (cond
     (symbol? form)
     (resolve-symbol ctx env form loc)

     (list? form)
     (eval-list ctx env form loc)

     (vector? form)
     (mapv (fn [v] (eval-form ctx env v loc)) form)

     (map? form)
     (into {}
           (map (fn [[k v]]
                  [(eval-form ctx env k loc) (eval-form ctx env v loc)]))
           form)

     :else
     form)))

(defn- eval-source
  [ctx env src]
  (assert-built! ctx)
  (eval-body ctx env (reader/read-all src)))

(defn eval-string
  ([ctx source] (eval-string ctx {} source))
  ([ctx env source] (eval-source ctx env (reader/source source))))

(defn eval-file
  ([ctx filename] (eval-file ctx {} filename))
  ([ctx env filename]
   (eval-source ctx env (reader/read-file filename))))

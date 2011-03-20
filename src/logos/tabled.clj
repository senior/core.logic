(ns logos.tabled
  (:refer-clojure :exclude [reify == inc])
  (:use logos.minikanren)
  (:import [logos.minikanren Choice]))

(defprotocol ISuspendedStream
  (ready? [this]))

(deftype SuspendedStream [cache ansv* f]
  ISuspendedStream
  (ready? [this]
          (not (= @cache ansv*))))

(defn ss? [x]
  (instance? SuspendedStream x))

(defn to-w [s]
  (into [] s))

(defn w? [x]
  (vector? x))

(defn w-check [w sk fk]
  (loop [w w a []]
    (cond
     (empty? w) (fk)
     (ready? (first w)) (sk (fn []
                              (let [^SuspendedStream ss (first w)
                                    f (.f ss)
                                    w (to-w (concat a (rest w)))]
                                (if (empty? w)
                                  (f)
                                  (mplus (f) (fn [] w)))))))
    :else (recur (rest w) (conj a (first w)))))

;; TODO: consider the concurrency implications here more closely

(defmacro tabled [args & body]
  `(fn [~@args]
     (let [table# (atom {})
           argv# ~args]
       (fn [a#]
         (let [key# (reify a# argv#)
               cache# (get @table# key#)]
           (if (nil? cache#)
             (let [cache# (atom {})]
               (swap! assoc table# key# cache#)
               ((exist []
                   ~@body
                   (master argv# cache#)) a#))
             (reuse a# argv# cache#)))))))

(defn master [argv cache]
  (fn [a]
    (and
     (every? (fn [ansv]
               (not (alpha-equiv? a argv ansv)))
      @cache)
     (do
       (swap! cache conj @cache)
       a))))

(defprotocol ITabled
  (-reify-tabled [this v])
  (reify-tabled [this v])
  (alpha-equiv? [this x y])
  (master [this argv cache])
  (reuse [this argv cache start end])
  (subunify [this arg ans]))

;; CONSIDER: subunify, reify-term-tabled, extending all the necessary types to them

(extend-type logos.minikanren.Substitutions
  ITabled

  (-reify-tabled [this v]
             (let [v (walk this v)]
               (cond
                (lvar? v) (ext-no-check this v (lvar (count (.s this))))
                (coll? v) (-reify-tabled
                           (-reify-tabled this (first v))
                           (next v))
                :else this)))

  (reify-tabled [this v]
                (let [v (walk* this v)]
                  (walk* (-reify-tabled empty-s v) v)))

  (alpha-equiv [this x y]
               (= (reify this x) (reify this y)))

  (reuse [this argv cache start end]
         (let [start (or start @cache)
               end   (or end [])]
           (letfn [(reuse-loop [ansv*]
                     (if (= ansv* end)
                       [(SuspendedStream. cache start
                          (fn [] (reuse this argv cache @cache start)))]
                       (Choice. (subunify this argv (reify-tabled this (first ansv*)))
                                (fn [] (reuse-loop (rest ansv*))))))]
             (reuse-loop start))))

  (subunify [this arg ans]
            (let [arg (walk this arg)]
              (cond
               (= arg ans) this
               (lvar? arg) (ext-no-check this arg ans)
               (coll? arg) (subunify
                            (subunify this (rest arg) (rest ans))
                            (first arg) (first ans))
               :else this))))

(extend-type clojure.lang.IPersistentVector
  IBind
  (bind [this g]
        (map (fn [^SuspendedStream ss]
               (SuspendedStream. (.cache ss) (.ansv* ss)
                                 (fn [] (bind ((.f ss)) g))))
             this))
  IMPlus
  (mplus [this f]
         (let [a-inf (f)]
           (if (w? a-inf)
             (to-w (concat a-inf this))
             (mplus a-inf (fn [] this)))))
  ITake
  (take* [a] ()))

(comment
  (let [x (lvar 'x)
        y (lvar 'y)
        s (to-s [[x 1] [y 2]])]
    (reify s [x y]))
  )
;;; Copyright (C) 2011 Nils Bertschinger

;;; This file is part of Probabilistic-Clojure

;;; Probabilistic-Clojure is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU Lesser General Public License as published by
;;; the Free Software Foundation, either version 3 of the License, or
;;; (at your option) any later version.

;;; Probabilistic-Clojure is distributed in the hope that it will be useful,
;;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;; GNU Lesser General Public License for more details.

;;; You should have received a copy of the GNU Lesser General Public License
;;; along with Probabilistic-Clojure.  If not, see <http://www.gnu.org/licenses/>.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Probabilistic programming using constraint propagation
;;;; to efficiently track dependencies between choice points
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns
    ^{:author "Nils Bertschinger"
      :doc "Part of probabilistic-clojure.embedded which implements a couple
of probabilistic choice points."}
  probabilistic-clojure.embedded.choice-points
  (:use [probabilistic-clojure.embedded.api :only (def-prob-cp det-cp gv memo)]
	[probabilistic-clojure.utils.stuff :only (ensure-list error)])
  ;; (:require [incanter.stats :as stat]) ; This does not work inside macros!!!
  (:import org.apache.commons.math.special.Gamma))
  ;; (:import cern.jet.stat.tdouble.Gamma))

(in-ns 'probabilistic-clojure.embedded.choice-points)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Bernoulli distribution
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def-prob-cp flip-cp [p]
  :sampler [] (< (rand) p)
  :calc-log-lik [bool] (Math/log (if bool p (- 1 p)))
  ;; proposer returns a vector of [new-value forward-log-prob backward-log-prob]
  :proposer [bool] [(not bool) 0 0])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Discrete distribution
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn log-pdf-discrete [x dist]
  (if (contains? dist x)
    (Math/log (dist x))
    (Math/log 0)))

(def-prob-cp discrete-cp [dist]
  :sampler [] (probabilistic-clojure.utils.sampling/sample-from dist)
  :calc-log-lik [x] (probabilistic-clojure.embedded.choice-points/log-pdf-discrete x dist)
  :proposer [old-x] (let [prop-dist (probabilistic-clojure.utils.sampling/normalize (assoc dist old-x 0))
			  new-x (probabilistic-clojure.utils.sampling/sample-from prop-dist)]
		      [new-x
		       (probabilistic-clojure.embedded.choice-points/log-pdf-discrete new-x prop-dist)
		       (probabilistic-clojure.embedded.choice-points/log-pdf-discrete
                        old-x (probabilistic-clojure.utils.sampling/normalize (assoc dist new-x 0)))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Gaussian distribution
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *gaussian-proposal-sdev* 0.7)

(def-prob-cp gaussian-cp [mu sdev]
  :sampler [] (incanter.stats/sample-normal 1 :mean mu :sd sdev)
  :calc-log-lik [x] (Math/log (incanter.stats/pdf-normal x :mean mu :sd sdev))
  :proposer [old-x] (let [proposal-sd probabilistic-clojure.embedded.choice-points/*gaussian-proposal-sdev*
			  new-x (incanter.stats/sample-normal 1 :mean old-x :sd proposal-sd)]
		      [new-x
		       (Math/log (incanter.stats/pdf-normal new-x :mean old-x :sd proposal-sd))
		       (Math/log (incanter.stats/pdf-normal old-x :mean new-x :sd proposal-sd))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Dirichlet distribution
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn log-pdf-dirichlet [ps alphas]
  (assert (= (count ps) (count alphas))
	  "Dimension mismatch in log-pdf-dirichlet!")
  (let [log-gamma (fn [x] (Gamma/logGamma x))
	norm (- (reduce + (map log-gamma alphas))
		(log-gamma (reduce + alphas)))]
    (- (reduce + (map (fn [p a] (* (- a 1) (Math/log p))) ps alphas))
       norm)))

(def ^:dynamic *dirichlet-proposal-factor* 42)
(def ^:dynamic *dirichlet-proposal-shift* 0.001) ; found a paper claiming that this is good
(def ^:dynamic *dirichlet-initial-factor*  1)

(def-prob-cp dirichlet-cp [alphas]
  :sampler [] (first
	       (incanter.stats/sample-dirichlet
                2
                (map (partial * probabilistic-clojure.embedded.choice-points/*dirichlet-initial-factor*)
                     alphas)))
  :calc-log-lik [ps] (probabilistic-clojure.embedded.choice-points/log-pdf-dirichlet ps alphas)
  :proposer [old-ps] (letfn [(proposal-alphas [alphas]
			       (for [a alphas]
				 (+ (* probabilistic-clojure.embedded.choice-points/*dirichlet-proposal-factor* a)
				    probabilistic-clojure.embedded.choice-points/*dirichlet-proposal-shift*)))]
		       (let [new-ps (first (incanter.stats/sample-dirichlet 2 (proposal-alphas old-ps)))]
			 [new-ps
			  (probabilistic-clojure.embedded.choice-points/log-pdf-dirichlet new-ps (proposal-alphas old-ps))
			  (probabilistic-clojure.embedded.choice-points/log-pdf-dirichlet old-ps (proposal-alphas new-ps))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Beta distribution
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def-prob-cp beta-cp [alpha beta]
  :sampler [] (incanter.stats/sample-beta 1 :alpha alpha :beta beta)
  :calc-log-lik [x] (Math/log (incanter.stats/pdf-beta x :alpha alpha :beta beta))
  :proposer [old-x] (let [new-x (incanter.stats/sample-beta 1 :alpha alpha :beta beta)]
		      [new-x
		       (Math/log (incanter.stats/pdf-beta new-x :alpha alpha :beta beta))
		       (Math/log (incanter.stats/pdf-beta old-x :alpha alpha :beta beta))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Dirichlet process
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pick-a-stick [alpha idx]
  (let [stick (beta-cp [:pick :stick idx] 1 alpha)
	stop  (flip-cp [:pick :stop idx] (gv stick))]
    (det-cp [:pick idx]
      (if (gv stop)
	idx
	(gv (pick-a-stick alpha (inc idx)))))))

(defmacro dirichlet-process [tag alpha base]
  `(memo [~tag ~alpha]
	 ~base
	 (gv (pick-a-stick ~alpha 1))))

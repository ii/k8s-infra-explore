(ns houndwork.main
  (:require [babashka.curl :as curl]
            [clojure.string :as str]
            [babashka.process :refer [sh]]
            [cheshire.core :as json]))

;; Get all instances of k8s.gcr.io across all the k8s repos and parse the result into json
(def hound
  (json/parse-string
   (:body (curl/get "https://cs.k8s.io/api/v1/search?stats=fosho&repos=*&rng=%3A20&q=k8s.gcr.io&i=nope&files=&excludeFiles=vendor%2F"))
   true))

;; From the json, get all the instances of the "Line" key. This line will have k8s.gcr.io somewhere in it.
(def image-lines (apply concat (map #(map :Line (flatten %)) (map #(map :Matches %) (map #(:Matches (second %)) (:Results hound))))))

;; Filter those results down to where it's clearly an image that's being referenced.  This gives us a sequence of these images
(def images (set (apply concat (filter not-empty (map #(re-find #"(k8s\.gcr\.io\/[^:]+:[a-zA-Z0-9.]+)" %) image-lines)))))

(defn crane-pull-image
  "attempt to pull the image, sending it to /dev/null.  Will return an object with an exit code and any error that came up."
  [image]
  (sh (str "crane pull "image" /dev/null")))

(defn test-registry
  "create object showing whether we got exit 0 and, if not, any errors that came up"
  [image]
  (let [reg-check (crane-pull-image image)]
    (assoc {}
           :success (= 0 (:exit reg-check))
           :error (:err reg-check))))

(defn test-registries
  "for an image, run test-registry for the k8s.gcr.io and registry.k8s.io registries"
  [image]
  (let [old-reg-check (test-registry image)
        new-reg-check (test-registry (str/replace image #"k8s.gcr.io" "registry.k8s.io"))]
    (assoc {}
     :image (str/replace image #"k8s.gcr.io/" "")
     :k8sGcrPull old-reg-check
     :registryK8sPull new-reg-check)))

(comment
;; This needs to be improved, likely with pmap instead of map. It'll just do it one by one, right now, which means this is slooooow.
  ;;  we take our images from above and test each of them against both registries, printing the resulting json to a file called images.json
  (spit "images.json"
        (json/generate-string (map test-registries images)))
  )
;; if we've already done that work above, then bring it into the file for further parsing.
(def images-plus (json/parse-string (slurp "images.json") true))

;; filter the list to only those that can be pulled from registry.k8s.io successfully, then count how many we have.
(count
 (filter #(= true (-> % :registryK8sPull :success)) images-plus))

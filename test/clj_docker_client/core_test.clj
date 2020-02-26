;   This file is part of clj-docker-client.
;
;   clj-docker-client is free software: you can redistribute it and/or modify
;   it under the terms of the GNU Lesser General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   clj-docker-client is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU Lesser General Public License for more details.
;
;   You should have received a copy of the GNU Lesser General Public License
;   along with clj-docker-client. If not, see <http://www.gnu.org/licenses/>.

(ns clj-docker-client.core-test
    (:require [clojure.test :refer :all]
        [clojure.java.io :as io]
        [clojure.core.async :as async]
        [clojure.java.io :as io]
        [clj-docker-client.core :refer :all]) (:import (java.io InputStream))
    (:import (java.util Arrays)
             (java.io IOException)))

(def latest-version "v1.40")

(deftest docker-connection
  (testing "successful connection to the socket"
    (is (map? (connect {:uri "unix:///var/run/docker.sock"}))))

  (testing "connection with usupported protocol"
    (is (thrown? IllegalArgumentException
                 (connect {:uri "http://this-does-not-work"}))))

  (testing "connection with set timeouts"
    (let [{:keys [client]} (connect {:uri             "unix:///var/run/docker.sock"
                                     :connect-timeout 10
                                     :read-timeout    2000
                                     :write-timeout   3000})]
      (is (and (= 10 (.connectTimeoutMillis client))
               (= 2000 (.readTimeoutMillis client))
               (= 3000 (.writeTimeoutMillis client))
               (= 0 (.callTimeoutMillis client)))))))

(deftest fetch-categories
  (testing "listing all available categories in latest version"
    (is (coll? (categories))))

  (testing "listing all available categories in fixed version"
    (is (coll? (categories latest-version)))))

(deftest client-construction
  (testing "making a container client of the latest version"
    (is (map? (client {:category :containers
                       :conn     (connect {:uri "unix:///var/run/docker.sock"})}))))

  (testing "making a container client of a specific version"
    (is (map? (client {:category    :containers
                       :conn        (connect {:uri "unix:///var/run/docker.sock"})
                       :api-version latest-version})))))

(deftest fetching-ops
  (testing "fetching ops for the latest container client"
    (let [client (client {:category :containers
                          :conn     (connect {:uri "unix:///var/run/docker.sock"})})
          result (ops client)]
      (is (every? keyword? result)))))

(deftest fetching-docs
  (testing "fetching docs for the latest ContainerList op"
    (let [client (client {:category :containers
                          :conn (connect {:uri "unix:///var/run/docker.sock"})})
          result (doc client :ContainerList)]
      (is (and (contains? result :doc)
               (contains? result :params))))))

(defn pull-image
  [name]
  (let [images (client {:category :images
                        :conn     (connect {:uri "unix:///var/run/docker.sock"})})]
    (invoke images {:op     :ImageCreate
                    :params {:fromImage name}})))

(defn delete-image
  [name]
  (let [images (client {:category :images
                        :conn     (connect {:uri "unix:///var/run/docker.sock"})})]
    (invoke images {:op     :ImageDelete
                    :params {:name  name
                             :force true}})))

(defn delete-container
  [id]
  (let [containers (client {:category :containers
                            :conn     (connect {:uri "unix:///var/run/docker.sock"})})]
    (invoke containers {:op     :ContainerDelete
                        :params {:id    id
                                 :force true}})))

(deftest invoke-ops
  (let [conn (connect {:uri "unix:///var/run/docker.sock"})]
    (testing "invoke an op with no params"
      (let [pinger (client {:category :_ping
                            :conn     conn})]
        (is (= "OK"
               (invoke pinger {:op :SystemPing})))))

    (testing "invoke an op with non-stream params"
      (let [containers (client {:category :containers
                                :conn     conn})
            image      "busybox:musl"
            cname      "conny"]
        (pull-image image)
        (is (contains? (invoke containers {:op     :ContainerCreate
                                           :params {:name cname
                                                    :body {:Image image
                                                           :Cmd   "ls"}}})
                       :Id))
        (delete-container cname)
        (delete-image image)))

    (testing "invoke an op with stream params"
      (let [containers (client {:category :containers
                                :conn     conn})
            image      "busybox:musl"
            cname      "conny"]
        (pull-image image)
        (invoke containers {:op     :ContainerCreate
                            :params {:name cname
                                     :body {:Image image
                                            :Cmd   "ls"}}})
        (is (= ""
               (invoke containers {:op     :PutContainerArchive
                                   :params {:id          cname
                                            :path        "/root"
                                            :inputStream (-> "test/clj_docker_client/test.tar.gz"
                                                             io/file
                                                             io/input-stream)}})))
        (delete-container cname)
        (delete-image image)))


    (testing "invoke an op asynchronously"
      (let [images (client {:category :images :conn conn})
            image  "busybox:musl"
            chan   (async/chan)]
        (pull-image image)
        (try
          (do
            (is (= "okhttp3.internal.connection.RealCall"
                   (-> images
                       (invoke {:op :ImageList
                                :async-fn (partial async/>!! chan)
                                :params {:digests true}})
                       type
                       .getName)))
            (is (int? (:Created (ffirst (async/alts!! [chan (async/timeout 3000)]))))))
          (finally (delete-image image)))))

    (testing "cancel asynchronous op"
      (let [events (client {:category :events :conn conn})
            image  "busybox:musl"
            chan   (async/chan)]
        (try
          (let [call (invoke events {:op :SystemEvents
                                     :async-fn (partial async/>!! chan)
                                     :as :stream})]
            (is (= "okhttp3.internal.connection.RealCall" (-> call type .getName)))
            (pull-image image)
            (let [stream (first (async/alts!! [chan (async/timeout 3000)]))
                  buffer-size 150
                  buffer (char-array buffer-size)]
              (is (instance? InputStream stream))
              (is (thrown? Exception
                           (let [reader (io/reader stream)]
                             ;; ensure a pull event is received
                             (is (= buffer-size (.read reader buffer 0 buffer-size)))
                             (is (.contains (String. (Arrays/copyOfRange buffer 0 buffer-size)) "pull"))
                             ;; cancel asynchronous request
                             (.cancel call)
                             ;; read what is left until it fails or times out
                             (when-let [exception
                                        (first (async/alts!! [(async/timeout 3000)
                                                              (async/thread
                                                                (try
                                                                  (while true (.read reader buffer 0 buffer-size))
                                                                  (catch Exception e e)))]))]
                               (throw exception)))))))
          (finally (delete-image image)))))))



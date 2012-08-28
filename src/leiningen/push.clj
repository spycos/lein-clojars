(ns leiningen.push
  (:require [clojure.java.io :as io])
  (:use [leiningen.jar :only [jar get-jar-filename]]
        [leiningen.pom :only [pom]])
  (:import (com.jcraft.jsch JSch JSchException Logger)
           (java.io File FileInputStream)))

(let [re-repo #"(?:([^@]+)@)?([^:]+)(?::(\d+))?(?::(.*))?"]
  (defn parse-repo [repo]
    (let [[_ user host port path] (re-matches re-repo
                                              (or repo "clojars@clojars.org"))]
      [(or user (System/getProperty "user.name"))
       host
       (if port (Integer/parseInt port) 22)
       (or path ".")])))

(defn- add-identities [jsch]
 (let [homedir (File. (System/getProperty "user.home"))
       leindir (File. homedir ".leiningen")
       sshdir (File. homedir ".ssh")]
   (doseq [dir [leindir sshdir]
           name ["id_rsa" "id_dsa" "identity"]
           :let [file (File. dir name)]
           :when (.exists file)]
     (try
      (.addIdentity jsch (str file))
      (println "Using SSH identity" (str file))
      (catch JSchException e
        (println "Skipping invalid SSH key" (str file)))))))

(defn- read-ack [in]
  (let [b (.read in)]
    (when-not (zero? b)
      (throw (Exception. (str "scp expected ACK but got " b))))))

(defn scp-send [repo & files]
  (let [jsch (doto (JSch.) (add-identities))
        [user host port path] (parse-repo repo)
        session (doto (.getSession jsch user host port)
                  (.setConfig "StrictHostKeyChecking" "no")
                  (.connect))
        channel (doto (.openChannel session "exec")
                  (.setCommand (str "scp -p -t " \" path \"))
                  (.setErrStream System/err true))
        in (.getInputStream channel)
        out (.getOutputStream channel)]
    (try
     (.connect channel)
     (read-ack in)
     (doseq [path files]
       (let [file (io/file path)]
         (.write out (.getBytes (str "C0644 " (.length file) " "
                                     (.getName file) "\n")))
         (.flush out)
         (read-ack in)

         (io/copy file out)
         (.write out 0)
	 (.flush out)
         (read-ack in)))
     (.close out)
     (.read in) ; wait for remote close
     (finally
      (.disconnect channel)
      (.disconnect session)))))

(defn- exit [code]
  (try
    (require 'leiningen.core.main)
    ((ns-resolve (the-ns 'leiningen.core.main) 'exit) code)
    (catch java.io.FileNotFoundException e
      code)))

(defn push
  "Push a jar to the Clojars.org repository over scp"
  [project & [repo]]
  (when (System/getProperty "scp.verbose")
    (JSch/setLogger (proxy [Logger] []
                      (isEnabled [level] true)
                      (log [level message] (println level message)))))
  (let [jarfile (get-jar-filename project)
        targetpath (.getParentFile (io/file jarfile))
        pomfile (io/file (:root project) "pom.xml")]
    (pom project)
    (jar project)
    (try
     (scp-send repo pomfile jarfile)
     (exit 0)
     (catch JSchException e
       (.printStackTrace e)
       (when (= (.getMessage e) "Auth fail")
         (println
          (str
           "\nIf you're having trouble authenticating, try using\n"
           "a key generated by 'lein keygen'.  lein-clojars doesn't\n"
           "work yet with DSA or passphrased keys.  I'm working\n"
           "on fixing this. You can also push directly with scp:\n\n"
           "lein pom\n"
           "scp " pomfile " " jarfile " clojars@clojars.org:" )))
        (exit -1)))))

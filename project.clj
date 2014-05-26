(defproject moves-beeminder "0.1.0-SNAPSHOT"
	:description "FIXME: write description"
	:url "http://example.com/FIXME"
	:license {:name "AGPL v3" :url ""}
	:dependencies [
		[org.clojure/clojure "1.5.1"]
		[org.clojure/data.json "0.2.3"]
		[stuarth/clj-oauth2 "0.3.2" :exclusions [org.clojure/data.json]]
		[cheshire "5.3.1"]
		[de.ubercode.clostache/clostache "1.3.1"]
		[compojure "1.1.6"]
		[http-kit "2.1.16"]
		[com.taoensso/carmine "2.4.5"]
		[ring "1.2.1"]
		[clj-time "0.6.0"]
	]
	:uberjar-name "moves-beeminder-standalone.jar"
	:plugins [[lein-ring "0.8.7"]]
	:main moves-beeminder.core
	:ring {:handler moves-beeminder.core/app :port 8080}
	:min-lein-version "2.0.0"
)

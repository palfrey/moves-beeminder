(ns moves-beeminder.core
	(:use
		[compojure.handler :only [site]]
		[compojure.core :only [defroutes GET POST]]
		[org.httpkit.server :only [run-server]]
		[clostache.parser :as parser]
		[moves-beeminder.authentication :as auth]
		[ring.middleware.params :only [wrap-params]]
		[ring.middleware.keyword-params :only [wrap-keyword-params]]
		[ring.middleware.cookies :only [wrap-cookies]]
		[ring.middleware.stacktrace :only [wrap-stacktrace]]
		[ring.util.response :only [redirect set-cookie]]
		[clojure.data.json :only [write-str read-str]]
		[clojure.walk :only [keywordize-keys]]
		[ring.middleware.session.cookie :only [cookie-store]]
		[ring.middleware.session :only [wrap-session]]
		[taoensso.carmine :as car :refer (wcar)]
		[moves-beeminder.config]
	 	[clojure.string :only [replace blank?]]
	)
	(:require
		[clj-time.core :as time]
		[clj-time.coerce :as coerce]
		[clj-time.format :as format]
		[compojure.route :as route :only [files not-found]]
		[org.httpkit.client :as http]
		[clojure.pprint :as pprint]
	)
)

(defn auth-callback [req]
	(let [token (auth/google-access-token req)]
		(assoc
			(redirect "/")
			:session {
				:token (write-str token)
				:email (auth/google-user-email token)
			}
		 )
	 )
)

(defn get-session [req key]
	(-> req :session (get key))
)

(defn get-token [req]
	(keywordize-keys (read-str (get-session req :token)))
)

(def server-conn {:spec redis-spec})
(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

(defn redis-key [& args]
	(apply car/key (map #(replace % "." "_") args))
)

(defn kv-to-hashmap [kv]
	(apply merge (map #(hash-map (keyword (:key %)) (:value %)) kv))
)

(defn parse-int [s]
	(Long/parseLong (re-find #"\d+" s))
)

(defn moves-redis-key [email]
	(redis-key email "moves")
)

(defn beeminder-redis-key [email]
	(redis-key email "beeminder")
)

(defn compact-moves-date [data]
	(:steps (first (filter #(= (:activity %) "walking") (keywordize-keys data))))
)

(defn compact-moves-data [data]
	(apply merge (map #(sorted-map-by time/after? (format/parse (format/formatter "yyyyMMdd") (:date %)) (compact-moves-date (:summary %))) (keywordize-keys data)))
)

(defn beeminder-goals-list [access-token]
  (let [raw-data (keywordize-keys (read-str (:body @(http/get (str "https://www.beeminder.com/api/v1/users/me/goals.json?access_token=" access-token)))))]
	(apply merge (map #(sorted-map (:slug %) (:title %)) raw-data))
  )
)

(defn pp [data]
  (with-out-str (pprint/pprint data))
)

(defn displayable-hashmap [hashmap]
  (map #(hash-map :key (first %) :value (second %)) (seq hashmap))
)

(defn beeminder-add-no-goal [goals]
	(assoc goals "" "No goal set")
)

(defn set-checked-goal [chosen-goal goals]
	(map #(assoc % :checked (if (= (:key %) chosen-goal) "checked" "")) goals)
)

(defn beeminder-get-token [email]
  (wcar* (car/hget (beeminder-redis-key email) :access_token))
)

(defn main-page [req]
	(let [email (get-session req :email)]
		(if (empty? email)
			(redirect (:uri auth/auth-req))
			(let [
					moves-account (wcar* (car/hget (moves-redis-key email) :user_id))
					moves-access-token (wcar* (car/hget (moves-redis-key email) :access-token))
					moves-data (if (blank? moves-access-token) {} (read-str (:body @(http/get "https://api.moves-app.com/api/1.1/user/summary/daily?pastDays=7" {:oauth-token moves-access-token}))))
					moves-data (compact-moves-data moves-data)
					beeminder-username (wcar* (car/hget (beeminder-redis-key email) :username))
					beeminder-access-token (beeminder-get-token email)
					beeminder-goals (beeminder-goals-list beeminder-access-token)
				  	beeminder-chosen-goal (wcar* (car/hget (beeminder-redis-key email) :chosen-goal))
				  	beeminder-chosen-goal (if (contains? beeminder-goals beeminder-chosen-goal) beeminder-chosen-goal "")
				]
				(render-resource "templates/index.html"
					 {
						:email email
						:base-url base-url
						:moves-account moves-account
						:moves-client-id moves-client-id
						:moves-data (map #(hash-map :key (format/unparse (format/formatters :date) (first %)) :value (second %)) (seq moves-data))
						:beeminder-username beeminder-username
						:beeminder-client-id beeminder-client-id
						:beeminder-goals (-> beeminder-goals beeminder-add-no-goal displayable-hashmap ((partial set-checked-goal beeminder-chosen-goal)))
					  	:beeminder-chosen-goal beeminder-chosen-goal
					  	:message (-> req :params :message)
					 }
				)
			)
		)
	)
)

(defn moves-auth-callback [req]
	(let [
			email (get-session req :email)
			code (-> req :params :code)
			post_url (str "https://api.moves-app.com/oauth/v1/access_token?grant_type=authorization_code&code="
										code "&client_id=" moves-client-id "&client_secret=" moves-client-secret
						  "&redirect_uri=" base-url "/moves_auth")
			result @(http/post post_url)
			body (read-str (:body result))
			{:keys [access_token expires_in refresh_token user_id]} (keywordize-keys body)
		 ]
		(do
			(wcar* (car/hmset (moves-redis-key email)
				:user_id user_id
				:access-token access_token
				:refresh_token refresh_token
				:expires (coerce/to-long (time/plus (time/now) (time/seconds expires_in)))
			))
			(redirect "/")
		)
	)
)

(defn beeminder-auth-callback [req]
	(let [
			email (get-session req :email)
			access_token (-> req :params :access_token)
			username (-> req :params :username)
		]
		(if (nil? username)
			(redirect (str "/beeminder_auth?access_token="
                   access_token "&username="
					(:username
						(keywordize-keys
							(read-str
								(:body
									@(http/get (str "https://www.beeminder.com/api/v1/users/me.json?access_token=" access_token))
								)
							)
						)
					)
				)
			 )
			(do
				(wcar* (car/hmset
             		(beeminder-redis-key email)
					:username username
					:access_token access_token
     			))
				(redirect "/")
			)
		 )
	 )
)

(defn beeminder-set-goal [req]
	(let [
		email (get-session req :email)
		goal (-> req :params :goal)
		goals (beeminder-goals-list (beeminder-get-token email))
		  ]
		(if (empty? email)
		  (redirect (:uri auth/auth-req))
		  (do
			(wcar* (car/hset (beeminder-redis-key email) :chosen-goal goal))
			(redirect
			 (str "/?message= " (if (= goal "")
										   "Successfully cleared Beeminder goal"
										   (str "Successfully set Beeminder goal \"" (goals goal) "\"")
										   ))
			)
		  )
		)
	)
)

(defroutes all-routes
	(GET "/" [] main-page)
	(GET "/authentication/callback" [] auth-callback)
	(GET "/moves_auth" [] moves-auth-callback)
	(GET "/beeminder_auth" [] beeminder-auth-callback)
  	(POST "/beeminder/set-goal" [] beeminder-set-goal)
	(route/files "/" {:root "public"})
	;(route/not-found "Page not found")
)

(def app (-> #'all-routes wrap-keyword-params wrap-params wrap-cookies wrap-stacktrace (wrap-session {:store (cookie-store {:key "a 16-byte secret"})}) ))

(defn -main [port]
  (run-server app {:port (Integer. port)})
)

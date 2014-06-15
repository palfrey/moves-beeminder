(ns moves-beeminder.core
	(:use
		[compojure.handler :only [site]]
		[compojure.core :only [defroutes GET POST]]
		[org.httpkit.server :only [run-server]]
		[clostache.parser :as parser]
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
	)
	(:require
		[clj-time.core :as time]
		[clj-time.coerce :as coerce]
		[clj-time.format :as format]
		[compojure.route :as route :only [files not-found]]
		[org.httpkit.client :as http]
		[clojure.pprint :as pprint]
		[clojure.string :as str]
	)
)

(defn get-session [req key]
	(-> req :session (get key))
)

(def server-conn {:spec redis-spec})
(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

(defn redis-key [& args]
	(apply car/key (map #(str/replace % "." "_") args))
	)

(defn kv-to-hashmap [kv]
	(apply merge (map #(hash-map (keyword (:key %)) (:value %)) kv))
	)

(defn parse-int [s]
	(Long/parseLong (re-find #"\d+" s))
	)

(defn moves-redis-key [username]
	(redis-key username "moves")
	)

(defn beeminder-redis-key [username]
	(redis-key username "beeminder")
	)

(defn compact-moves-date [data]
	(:steps (first (filter #(= (:activity %) "walking") (keywordize-keys data))))
	)

(defn compact-moves-data [data]
	(apply merge (map #(sorted-map-by time/after? (format/parse (format/formatter "yyyyMMdd") (:date %)) (compact-moves-date (:summary %))) (keywordize-keys data)))
	)

(def beeminder-base "https://www.beeminder.com/api/v1/users/me")

(defn beeminder-goals-list [access-token]
	(let
		[
			page @(http/get (str beeminder-base "/goals.json?access_token=" access-token))
			raw-data (-> page :body read-str keywordize-keys)
		]
		(if (not= (:status page) 200)
			nil
			(apply merge (map #(sorted-map (:slug %) (:title %)) raw-data))
		)
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
	(let
		[
			beeminder-username (get-session req :beeminder-username)
			logged-in (not (nil? beeminder-username))
			moves-account (if logged-in (wcar* (car/hget (moves-redis-key beeminder-username) :user_id)))
			moves-access-token (if logged-in (wcar* (car/hget (moves-redis-key beeminder-username) :access-token)))
			beeminder-chosen-goal (if logged-in (wcar* (car/hget (beeminder-redis-key beeminder-username) :chosen-goal)))
		]
		(render-resource
			"templates/index.html"
			{
				:base-url              base-url
				:moves-account         moves-account
				:moves-client-id       moves-client-id
				:beeminder-client-id   beeminder-client-id
				:beeminder-username    beeminder-username
				:beeminder-chosen-goal beeminder-chosen-goal
				:message               (-> req :params :message)
			}
		)
	)
)

(defn moves-auth-callback [req]
	(let
		[
			username (get-session req :beeminder-username)
			code (-> req :params :code)
			post_url (str "https://api.moves-app.com/oauth/v1/access_token?grant_type=authorization_code&code="
						  code "&client_id=" moves-client-id "&client_secret=" moves-client-secret
						  "&redirect_uri=" base-url "/moves_auth")
			result @(http/post post_url)
			body (read-str (:body result))
			{:keys [access_token expires_in refresh_token user_id]} (keywordize-keys body)
			]
		(do
			(wcar*
				(car/hmset
					(moves-redis-key username)
					:user_id user_id
					:access-token access_token
					:refresh_token refresh_token
					:expires (coerce/to-long (time/plus (time/now) (time/seconds expires_in)))
				)
			)
			(redirect "/")
		)
	)
)

(defn beeminder-auth-callback [req]
	(let
		[
			access_token (-> req :params :access_token)
			username (-> req :params :username)
			]
		(if (nil? username)
			(redirect (str "/beeminder_auth?access_token=" access_token "&username="
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
				(wcar*
					(car/hmset
						(beeminder-redis-key username)
						:username username
						:access_token access_token
					)
				)
				(assoc
					(redirect "/")
					:session
					{
						:beeminder-username username
					}
				)
			)
		)
	)
)

(defn beeminder-set-goal [req]
	(let
		[
			username (get-session req :beeminder-username)
			goal (-> req :params :goal)
			goals (beeminder-goals-list (beeminder-get-token username))
		]
		(wcar* (car/hset (beeminder-redis-key username) :chosen-goal goal))
		(redirect
			(str "/?message= "
				(if (= goal "")
					"Successfully cleared Beeminder goal"
					(str "Successfully set Beeminder goal \"" (goals goal) "\"")
				)
			)
		)
	)
)

(defn dt-at-midnight [dt]
	(time/date-time (time/year dt) (time/month dt) (time/day dt))
	)

(defn get-moves-data [email]
	(let
		[
			moves-account (wcar* (car/hget (moves-redis-key email) :user_id))
			moves-access-token (wcar* (car/hget (moves-redis-key email) :access-token))
			moves-data (read-str (:body @(http/get "https://api.moves-app.com/api/1.1/user/summary/daily?pastDays=7" {:oauth-token moves-access-token})))
			]
		(compact-moves-data moves-data)
		)
	)

(defn import-data [email]
	(let
		[
			moves-data (get-moves-data email)
			beeminder-username (wcar* (car/hget (beeminder-redis-key email) :username))
			beeminder-access-token (beeminder-get-token email)
			beeminder-goals (beeminder-goals-list beeminder-access-token)
			beeminder-chosen-goal (wcar* (car/hget (beeminder-redis-key email) :chosen-goal))
			beeminder-chosen-goal (if (contains? beeminder-goals beeminder-chosen-goal) beeminder-chosen-goal "")
			]
		(if (= beeminder-chosen-goal "")
			(throw (Exception. "No valid goal set!"))
			(let
				[
					raw-data
					(-> @(http/get
						(str beeminder-base "/goals/"
							beeminder-chosen-goal "/datapoints.json?access_token=" beeminder-access-token)
						)
						:body read-str keywordize-keys
					)
					datapoints (apply merge (map #(sorted-map (coerce/to-epoch (dt-at-midnight (coerce/from-long (* (:timestamp %) 1000)))) %) raw-data))
				]
				{
					:goal beeminder-chosen-goal
					:user beeminder-username
					:results
					(for
						[key (keys moves-data)]
						(let [key-when (coerce/to-epoch key)]
							(cond
								(nil? (moves-data key))
									{:warning (str "empty moves data for " (format/unparse (format/formatters :date) key))}
								(not (contains? datapoints key-when))
									@(http/post
										(str beeminder-base "/goals/" beeminder-chosen-goal "/datapoints.json")
										{:form-params
											{
												"access_token" beeminder-access-token
												"timestamp"    key-when
												"value"        (moves-data key)
												"comment"      (str "Set by importer at " (format/unparse (format/formatters :rfc822) (time/now)))
											}
										}
									)
								(not= (int (:value (datapoints key-when))) (moves-data key))
									@(http/put
										(str beeminder-base "/goals/" beeminder-chosen-goal "/datapoints/" (:id (datapoints key-when)) ".json?access_token=" beeminder-access-token)
										{:query-params
											{
												"timestamp" key-when
												"value"     (moves-data key)
												"comment"   (str "Set by importer at " (format/unparse (format/formatters :rfc822) (time/now)))
											}
										}
									)
							)
						)
					)
				}
			)
		)
	)
)

(defn import-page [req]
	(let
		[
			username (get-session req :beeminder-username)
			{:keys [goal user results]} (import-data username)
		]
		(render-resource
			"templates/import.html"
			{
				:beeminder-username    user
				:beeminder-chosen-goal goal
				:import-results        (pp results)
			}
		)
	)
)

(defn substring?
	"is 'sub' in 'str'?"
	[sub str]
	(not= (.indexOf str sub) -1)
)

(defn beeminder-auth-fix [req]
	(let
		[
			keys (wcar* (car/keys "*:beeminder"))
			emails (filter #(substring? "@" %) (map #(first (str/split % #":")) keys))
		]
		(doseq [email emails]
			(let
				[
					beeminder (if (not (nil? email)) (-> (beeminder-redis-key email) car/hgetall* wcar* keywordize-keys))
					username (:username beeminder)
					moves (-> (moves-redis-key email) car/hgetall* wcar* keywordize-keys)
				]
				(if (not (nil? username))
					(wcar*
						(car/hmset* (beeminder-redis-key username) beeminder)
						(car/hmset* (moves-redis-key username) moves)
					)
				)
			)
		)
		(render-resource "templates/authfix.html"
			{
				:emails (list emails)
			}
		)
	)
)

(def beeminder-redirect
	(redirect "/?message=Need to authenticate to Beeminder")
)

(defn beeminder-clear-user [req username]
	(wcar* (car/del (beeminder-redis-key username)))
	(println beeminder-redirect)
	(assoc beeminder-redirect
		:session {}
	)
)

(defn beeminder-choose-goal [req]
	(let
		[
			username (get-session req :beeminder-username)
		]
		(if (nil? username)
			beeminder-redirect
			(let
				[
					beeminder-access-token (beeminder-get-token username)
					logged-in (not (nil? beeminder-access-token))
					beeminder-goals (if logged-in (beeminder-goals-list beeminder-access-token) nil)
					beeminder-chosen-goal (wcar* (car/hget (beeminder-redis-key username) :chosen-goal))
					beeminder-goals (if logged-in (-> beeminder-goals beeminder-add-no-goal displayable-hashmap ((partial set-checked-goal beeminder-chosen-goal))))
				]
				(if (nil? beeminder-goals)
					(beeminder-clear-user req username)
					(render-resource
						"templates/beeminder-goals.html"
						{
							:beeminder-goals beeminder-goals
						}
					)
				)
			)
		)
	)
)

(defroutes all-routes
	(GET "/" [] main-page)
	(GET "/moves_auth" [] moves-auth-callback)
	(GET "/beeminder_auth" [] beeminder-auth-callback)
	(POST "/beeminder/set-goal" [] beeminder-set-goal)
	(GET "/beeminder/choose-goal" [] beeminder-choose-goal)
	(GET "/import" [] import-page)
	(GET "/beeminder-auth-fix" [] beeminder-auth-fix)
	(route/files "/" {:root "public"})
	(route/not-found "Page not found")
)

(def app (-> #'all-routes wrap-keyword-params wrap-params wrap-cookies wrap-stacktrace (wrap-session {:store (cookie-store {:key "a 16-byte secret"})})))

(defn -main [port]
	(run-server app {:port (Integer. port)})
	)

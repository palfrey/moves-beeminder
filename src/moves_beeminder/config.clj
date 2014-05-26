(ns moves-beeminder.config
  (:use
   [environ.core :only [env]]
  )
)

(def moves-client-id (env :moves-client-id))
(def moves-client-secret (env :moves-client-secret))

(def beeminder-client-id (env :beeminder-client-id))
(def beeminder-client-secret (env :beeminder-client-secret))

(def google-client-id (env :google-client-id))
(def google-client-secret (env :google-client-secret))

(def base-url (env :base-url))

(def redis-host (env :redis-host))
(def redis-port (env :redis-port))
(def redis-password (env :redis-password))

;(def redis-spec {:host redis-host :port redis-port :password redis-password})

(def redis-spec {:uri (env :rediscloud-url)})

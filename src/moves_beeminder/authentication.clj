(ns moves-beeminder.authentication
	(:use
		[moves-beeminder.secrets]
	)
	(:require
		[cheshire.core :refer [parse-string]]
		[clj-oauth2.client :as oauth2]
	)
)

(def login-uri
	"https://accounts.google.com")

(def google-com-oauth2
	{
  		:authorization-uri (str login-uri "/o/oauth2/auth")
		:access-token-uri (str login-uri "/o/oauth2/token")
		:redirect-uri "http://localhost:8080/authentication/callback"
		:client-id google-client-id
		:client-secret google-client-secret
		:access-query-param :access_token
		:scope ["email"]
		:grant-type "authorization_code"
		:access-type "online"
		:approval_prompt ""
  	}
 )

(def auth-req
	(oauth2/make-auth-request google-com-oauth2))

(defn google-access-token [request]
	(oauth2/get-access-token google-com-oauth2 (:params request) auth-req))

(defn google-user-email [access-token]
	(let [response (oauth2/get "https://www.googleapis.com/oauth2/v1/userinfo" {:oauth2 access-token :throw-exceptions true})]
		(get (parse-string (:body response)) "email")))

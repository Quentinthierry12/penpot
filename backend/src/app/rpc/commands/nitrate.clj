;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.nitrate
  "Nitrate API for Penpot. Provides nitrate-related endpoints to be called
   from Penpot frontend."
  (:require
   [app.auth.oidc :as oidc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.organization :as cto]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as-alias audit]
   [app.msgbus :as-alias mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.permissions :as perms]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.nitrate.emails-helper :as neh]
   [app.rpc.nitrate.organization-helper :as noh]
   [app.rpc.notifications :as notifications]
   [app.util.services :as sv]
   [app.common.uuid :as uuid]
   [buddy.core.codecs :as bc]
   [clojure.string :as str]))


(defn assert-is-owner [cfg profile-id team-id]
  (let [perms (teams/get-permissions cfg profile-id team-id)]
    (when-not (:is-owner perms)
      (ex/raise :type :validation
                :code :insufficient-permissions))))

(defn assert-not-default-team [cfg team-id]
  (let [team (teams/get-team-info cfg {:id team-id})]
    (when (:is-default team)
      (ex/raise :type :validation
                :code :cant-move-default-team))))


(def schema:connectivity
  [:map {:title "nitrate-connectivity"}
   [:licenses ::sm/boolean]])

(sv/defmethod ::get-nitrate-connectivity
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params [:map]
   ::sm/result schema:connectivity}
  [cfg _params]
  (nitrate/call cfg :connectivity {}))

(def ^:private schema:owned-organization
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:owner-id ::sm/uuid]
   [:team-count ::sm/int]
   [:member-count ::sm/int]])

(sv/defmethod ::get-owned-organizations
  "List organizations owned by the current profile."
  {::rpc/auth true
   ::doc/added "2.19"
   ::sm/params [:map]
   ::sm/result [:vector schema:owned-organization]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (db/exec! pool
            ["SELECT o.id,
                     o.name,
                     o.slug,
                     o.owner_id,
                     (SELECT count(*)
                        FROM organization_team_rel AS otr
                       WHERE otr.organization_id = o.id) AS team_count,
                     (SELECT count(*)
                        FROM organization_member_rel AS omr
                       WHERE omr.organization_id = o.id) AS member_count
                FROM organization AS o
               WHERE o.owner_id = ?
            ORDER BY o.created_at, o.id"
             profile-id]))

(def ^:private schema:create-organization-params
  [:map {:title "CreateOrganizationParams" :closed true}
   [:name [:and ::sm/text [:fn #(<= 1 (count %) 120)]]]])

(def ^:private schema:create-organization-result
  [:map {:title "CreateOrganizationResult"}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:default-team-id ::sm/uuid]])

(defn- organization-slug
  [name organization-id]
  (let [base (-> name
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-|-$" ""))
        suffix (subs (str organization-id) 0 8)]
    (str (if (str/blank? base) "organization" base) "-" suffix)))

(defn- assert-organization-owner!
  [cfg profile-id organization-id]
  (let [organization (db/get cfg :organization {:id organization-id})]
    (when-not (= profile-id (:owner-id organization))
      (ex/raise :type :validation
                :code :insufficient-permissions))
    organization))

(sv/defmethod ::create-organization
  "Create an organization and its owner's personal projects team."
  {::rpc/auth true
   ::doc/added "2.19"
   ::sm/params schema:create-organization-params
   ::sm/result schema:create-organization-result
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id name]}]
  (let [organization-id (uuid/next)
        slug            (organization-slug name organization-id)
        default-team    (teams/create-team cfg {:profile-id profile-id
                                                 :name "Personal Projects"
                                                 :is-default true})
        default-team-id (:id default-team)]
    (db/insert! cfg :organization
                {:id organization-id
                 :owner-id profile-id
                 :name name
                 :slug slug})
    (db/insert! cfg :organization-member-rel
                {:organization-id organization-id
                 :profile-id profile-id
                 :default-team-id default-team-id})
    (db/insert! cfg :organization-team-rel
                {:organization-id organization-id
                 :team-id default-team-id})
    (rph/with-meta
      (rph/wrap {:id organization-id
                 :name name
                 :slug slug
                 :default-team-id default-team-id})
      {::audit/name "create-organization"
       ::audit/props {:organization-id organization-id
                      :name name}})))

(sv/defmethod ::rename-organization
  "Rename an organization owned by the current profile."
  {::rpc/auth true
   ::sm/params [:map
                [:organization-id ::sm/uuid]
                [:name [:and ::sm/text [:fn #(<= 1 (count %) 120)]]]]
   ::sm/result [:map [:slug ::sm/text]]}
  [cfg {:keys [::rpc/profile-id organization-id name]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (let [slug (organization-slug name organization-id)]
    (db/update! cfg :organization {:name name :slug slug} {:id organization-id})
    (rph/with-meta (rph/wrap {:slug slug})
      {::audit/name "rename-organization"
       ::audit/props {:organization-id organization-id
                      :name name}})))

(sv/defmethod ::delete-organization
  "Delete an empty organization owned by the current profile."
  {::rpc/auth true
   ::db/transaction true
   ::sm/params [:map [:organization-id ::sm/uuid]]
   ::sm/result :nil}
  [cfg {:keys [::db/conn ::rpc/profile-id organization-id]}]
  (let [organization (assert-organization-owner! cfg profile-id organization-id)
        teams (db/query conn :organization-team-rel {:organization-id organization-id})]
    (when (seq teams)
      (ex/raise :type :validation
                :code :organization-must-be-empty
                :hint "Move or delete the organization's teams before deleting it"))
    (db/delete! conn :organization {:id organization-id})
    (rph/with-meta (rph/wrap)
      {::audit/name "delete-organization"
       ::audit/props {:organization-id organization-id
                      :name (:name organization)}})))

(sv/defmethod ::create-organization-team
  "Create a team under an organization owned by the current profile."
  {::rpc/auth true
   ::db/transaction true
   ::sm/params [:map
                [:organization-id ::sm/uuid]
                [:name [:and ::sm/text [:fn #(<= 1 (count %) 120)]]]]
   ::sm/result [:map
                [:id ::sm/uuid]
                [:name ::sm/text]]}
  [cfg {:keys [::rpc/profile-id organization-id name]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (let [team (teams/create-team cfg {:profile-id profile-id
                                     :name name
                                     :organization-id organization-id})]
    (rph/with-meta (rph/wrap (select-keys team [:id :name]))
      {::audit/name "create-team"
       ::audit/props {:organization-id organization-id
                      :team-id (:id team)
                      :team-name (:name team)}})))

(def ^:private schema:organization-sso-settings
  [:map
   [:organization-id ::sm/uuid]
   [:active ::sm/boolean]
   [:provider {:optional true} [:enum "oidc"]]
   [:client-id {:optional true} ::sm/text]
   [:client-secret {:optional true} ::sm/text]
   [:issuer {:optional true} ::sm/text]])

(sv/defmethod ::get-organization-sso-settings
  "Get the OIDC settings for an organization owned by the current profile."
  {::rpc/auth true
   ::sm/params [:map [:organization-id ::sm/uuid]]
   ::sm/result [:maybe [:map
                        [:active ::sm/boolean]
                        [:provider [:enum "oidc"]]
                        [:client-id ::sm/text]
                        [:issuer [:maybe ::sm/text]]]]}
  [cfg {:keys [::rpc/profile-id organization-id]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (when-let [sso (nitrate/call cfg :get-organization-sso {:organization-id organization-id})]
    (select-keys sso [:active :provider :client-id :issuer])))

(sv/defmethod ::set-organization-sso-settings
  "Save OIDC settings for an organization owned by the current profile."
  {::rpc/auth true
   ::sm/params schema:organization-sso-settings
   ::sm/result [:map [:active ::sm/boolean]]}
  [cfg {:keys [::rpc/profile-id organization-id active provider client-id client-secret issuer]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (let [current (nitrate/call cfg :get-organization-sso {:organization-id organization-id})
        config  {:organization-id organization-id
                 :active active
                 :provider (or provider "oidc")
                 :client-id (or client-id (:client-id current))
                 :client-secret (or client-secret (:client-secret current))
                 :issuer (or issuer (:issuer current))}]
    (when active
      (when (or (str/blank? (:client-id config))
                (str/blank? (:client-secret config))
                (str/blank? (:issuer config)))
        (ex/raise :type :validation
                  :code :invalid-sso-config
                  :hint "Issuer, client ID and client secret are required to enable OIDC SSO"))
      (when-not (oidc/is-organization-sso-config-valid? cfg config)
        (ex/raise :type :validation
                  :code :invalid-sso-config
                  :hint "The OIDC discovery document or client credentials could not be validated")))
    (when (or active current)
      (nitrate/call cfg :set-organization-sso config))
    (rpc/invalidate-organization-sso-cache-by-organization! organization-id)
    (session/clear-organization-sso-sessions! (::db/pool cfg) organization-id)
    (rph/with-meta (rph/wrap {:active active})
      {::audit/name "success-apply-organization-sso-changes"
       ::audit/props {:organization-id organization-id
                      :sso-active active
                      :issuer issuer}})))

(def ^:private default-organization-permissions
  {:create-teams "any"
   :delete-teams "onlyOwners"
   :move-teams "always"
   :send-invitations "ownersAndAdmins"
   :new-team-members "anyone"})

(def ^:private schema:organization-permissions
  [:map {:closed true}
   [:create-teams [:enum "any" "onlyMe"]]
   [:delete-teams [:enum "onlyOwners" "never"]]
   [:move-teams [:enum "always" "myOrganizations" "never"]]
   [:send-invitations [:enum "ownersAndAdmins" "owners"]]
   [:new-team-members [:enum "anyone" "members"]]])

(sv/defmethod ::get-organization-permissions-settings
  "Get the organization permission policy for its owner."
  {::rpc/auth true
   ::sm/params [:map [:organization-id ::sm/uuid]]
   ::sm/result schema:organization-permissions}
  [cfg {:keys [::rpc/profile-id organization-id]}]
  (let [organization (assert-organization-owner! cfg profile-id organization-id)]
    (merge default-organization-permissions
           (db/decode-json-pgobject (:permissions organization)))))

(sv/defmethod ::set-organization-permissions-settings
  "Update team and invitation policy for an organization owner."
  {::rpc/auth true
   ::sm/params [:map
                [:organization-id ::sm/uuid]
                [:permissions schema:organization-permissions]]
   ::sm/result schema:organization-permissions}
  [cfg {:keys [::rpc/profile-id organization-id permissions]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (let [permissions (merge default-organization-permissions permissions)]
    (db/update! cfg :organization {:permissions (db/json permissions)} {:id organization-id})
    (rph/with-meta (rph/wrap permissions)
      {::audit/name "update-organization-permissions"
       ::audit/props {:organization-id organization-id
                      :permissions permissions}})))

(def ^:private schema:organization-audit-event
  [:map
   [:id ::sm/uuid]
   [:event-name ::sm/text]
   [:profile-id [:maybe ::sm/uuid]]
   [:occurred-at ct/schema:inst]
   [:event ::sm/any]])

(sv/defmethod ::get-organization-audit-log
  "Return recent audit events for an organization owned by the current profile."
  {::rpc/auth true
   ::sm/params [:map [:organization-id ::sm/uuid]]
   ::sm/result [:vector schema:organization-audit-event]}
  [cfg {:keys [::rpc/profile-id organization-id]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (mapv #(update % :event db/decode-json-pgobject)
        (db/exec! cfg
                  ["SELECT id, event_name, profile_id, occurred_at, event
                     FROM organization_audit_log
                    WHERE organization_id = ?
                 ORDER BY occurred_at DESC, id DESC
                    LIMIT 100"
                   organization-id])))

(def ^:private schema:organization-admin-summary
  [:map
   [:teams [:vector [:map
                     [:id ::sm/uuid]
                     [:name ::sm/text]
                     [:member-count ::sm/int]]]]
   [:members [:vector [:map
                       [:id ::sm/uuid]
                       [:name [:maybe ::sm/text]]
                       [:email ::sm/email]
                       [:created-at ct/schema:inst]]]]])

(sv/defmethod ::get-organization-admin-summary
  "List teams and organization members for the owner console."
  {::rpc/auth true
   ::sm/params [:map [:organization-id ::sm/uuid]]
   ::sm/result schema:organization-admin-summary}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id organization-id]}]
  (assert-organization-owner! cfg profile-id organization-id)
  {:teams (db/exec! pool
                    ["SELECT t.id,
                             t.name,
                             (SELECT count(*)
                                FROM team_profile_rel AS tpr
                               WHERE tpr.team_id = t.id) AS member_count
                        FROM organization_team_rel AS otr
                        JOIN team AS t ON (t.id = otr.team_id)
                       WHERE otr.organization_id = ?
                         AND t.deleted_at IS NULL
                    ORDER BY t.name, t.id"
                     organization-id])
   :members (db/exec! pool
                      ["SELECT p.id,
                               p.fullname AS name,
                               p.email,
                               omr.created_at
                          FROM organization_member_rel AS omr
                          JOIN profile AS p ON (p.id = omr.profile_id)
                         WHERE omr.organization_id = ?
                           AND p.deleted_at IS NULL
                      ORDER BY lower(p.fullname), p.email"
                       organization-id])})

(sv/defmethod ::remove-organization-member
  "Remove an organization member from its teams and organization."
  {::rpc/auth true
   ::db/transaction true
   ::sm/params [:map
                [:organization-id ::sm/uuid]
                [:member-id ::sm/uuid]]
   ::sm/result :nil}
  [{:keys [::db/conn ::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id organization-id member-id]}]
  (assert-organization-owner! cfg profile-id organization-id)
  (let [membership (db/get conn :organization-member-rel
                           {:organization-id organization-id
                            :profile-id member-id})]
    (when-not membership
      (ex/raise :type :not-found :code :organization-member-does-not-exist))
    (when (= profile-id member-id)
      (ex/raise :type :validation :code :cannot-remove-organization-owner))
    (let [team-memberships (->> (db/query conn :organization-team-rel
                                          {:organization-id organization-id})
                                (keep (fn [{:keys [team-id]}]
                                        (when-let [permissions (teams/get-permissions conn member-id team-id)]
                                          {:id team-id :permissions permissions}))))]
      (doseq [{:keys [id permissions]} team-memberships]
        ;; The organization owner takes over the member's organization teams
        ;; before removal, preserving access to any projects and files.
        (when-not (teams/get-permissions conn profile-id id)
          (teams/add-profile-to-team!
           cfg
           (perms/assign-role-flags {:profile-id profile-id :team-id id} :owner)
           nil
           {:actor-id profile-id}))
        (teams/leave-team cfg (cond-> {:profile-id member-id :id id}
                                (:is-owner permissions) (assoc :reassign-to profile-id))))
      (doseq [{:keys [id]} team-memberships]
        (mbus/pub! msgbus
                   :topic member-id
                   :message {:type :team-membership-change
                             :change :removed
                             :team-id id
                             :team-name (:name (db/get conn :team {:id id}))}))
      (db/delete! conn :organization-member-rel
                  {:organization-id organization-id
                   :profile-id member-id}))
    (rph/with-meta (rph/wrap)
      {::audit/name "delete-organization-member"
       ::audit/props {:organization-id organization-id
                      :member-id member-id
                      :user-who-delete-member profile-id
                      :deleted-by-role "organization-owner"}})))

(def ^:private schema:subscription-warning
  [:maybe
   [:map {:title "SubscriptionWarning"}
    [:type {:optional true} ::sm/text]
    [:days-from-expiry {:optional true} ::sm/int]
    [:days-until-expiry {:optional true} ::sm/int]
    [:expiration-date {:optional true} ct/schema:inst]]])

(sv/defmethod ::get-subscription-warning
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params [:map]
   ::sm/result schema:subscription-warning}
  [cfg {:keys [::rpc/profile-id]}]
  (nitrate/call cfg :get-subscription-warning {:profile-id profile-id}))

(def ^:private schema:redeem-activation-code-params
  [:map {:title "RedeemActivationCodeParams"}
   [:activation-code ::sm/text]])

(def ^:private schema:redeem-activation-code-result
  [:map {:title "RedeemActivationCodeResult"}
   [:cancel-at [:maybe ct/schema:inst]]])

(sv/defmethod ::redeem-nitrate-activation-code
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:redeem-activation-code-params
   ::sm/result schema:redeem-activation-code-result}
  [cfg {:keys [::rpc/profile-id activation-code]}]
  (let [profile (db/get cfg :profile {:id profile-id})]
    (try
      (let [result (nitrate/call cfg :redeem-activation-code
                                 {:request-params  {:code      activation-code
                                                    :penpot-id profile-id
                                                    :email     (:email profile)}})]
        (when-not result
          (ex/raise :type :validation
                    :code :invalid-activation-code
                    :hint "The activation code is invalid, expired or fully redeemed"))
        result)
      (catch Exception cause
        (let [{:keys [type status]} (ex-data cause)]
          (if (= type :nitrate-http-error)
            (ex/raise :type :validation
                      :code (case status
                              410 :expired-activation-code
                              409 :used-activation-code
                              :invalid-activation-code)
                      :cause cause)
            (throw cause)))))))

(def ^:private activation-code-request-filename
  "penpot-activation-code-request.txt")

(sv/defmethod ::get-nitrate-activation-code-request
  "Returns a Base64-encoded JSON file requesting a Nitrate activation code.
  Payload includes nitrateId, publicKey, email and iat."
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params [:map]
   ::sm/result ::sm/text}
  [cfg {:keys [::rpc/profile-id]}]
  (let [profile          (db/get cfg :profile {:id profile-id})
        nitrate-identity (nitrate/call cfg :get-identity {})]
    (when-not nitrate-identity
      (ex/raise :type :validation
                :code :nitrate-identity-unavailable
                :hint "Unable to retrieve nitrate identity"))
    (-> (json/encode {:nitrate-id (:nitrate-id nitrate-identity)
                      :public-key (:public-key nitrate-identity)
                      :email      (:email profile)
                      :iat        (ct/seconds (ct/now))}
                     :key-fn json/write-camel-key)
        (bc/str->bytes)
        (bc/bytes->b64-str)
        (rph/wrap)
        (rph/with-header "content-type" "text/plain")
        (rph/with-header "content-disposition"
          (str "attachment; filename=\"" activation-code-request-filename "\"")))))

(def ^:private sql:prefix-team-name-and-unset-default
  "UPDATE team
      SET name = ? || name,
          is_default = FALSE
    WHERE id = ?;")

(def ^:private sql:get-member-teams-info
  "SELECT t.id,
          t.name,
          t.is_default,
          tpr.is_owner,
          (SELECT count(*)    FROM team_profile_rel WHERE team_id = t.id) AS num_members,
          (SELECT array_agg(profile_id) FROM team_profile_rel WHERE team_id = t.id) AS member_ids
     FROM team AS t
     JOIN team_profile_rel AS tpr ON (tpr.team_id = t.id)
    WHERE tpr.profile_id = ?
      AND t.id = ANY(?)
      AND t.deleted_at IS NULL")

(def ^:private sql:get-teams-files-counts
  "SELECT p.team_id, count(*) AS total
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ANY(?)
      AND f.deleted_at IS NULL
 GROUP BY p.team_id")

(defn- get-team-files-counts
  [conn team-ids]
  (if (seq team-ids)
    (let [ids-array (db/create-array conn "uuid" team-ids)]
      (->> (db/exec! conn [sql:get-teams-files-counts ids-array])
           (reduce (fn [acc {:keys [team-id total]}]
                     (assoc acc team-id (long total)))
                   {})))
    {}))

(defn- build-leave-organization-plan
  [{:keys [::db/conn]} default-team-id teams-to-delete keep-default-team-requested?]
  (let [all-teams     (cond-> (set teams-to-delete) default-team-id (conj default-team-id))
        files-counts  (get-team-files-counts conn all-teams)
        has-files?    (fn [id] (pos? (long (get files-counts id 0))))
        deletable     (remove has-files? teams-to-delete)
        keep-default? (or keep-default-team-requested?
                          (and default-team-id (has-files? default-team-id)))
        to-detach     (cond-> (into [] (remove (set deletable) teams-to-delete))
                        (and default-team-id keep-default?) (conj default-team-id))]
    {:deletable-team-ids       deletable
     :keep-default-team?       keep-default?
     :delete-default-team?     (boolean (and default-team-id (not keep-default?)))
     :detach-from-organization-team-ids to-detach}))

(defn get-leave-organization-summary
  [cfg default-team-id teams-to-delete teams-to-transfer-count teams-to-exit-count]
  (let [{:keys [deletable-team-ids detach-from-organization-team-ids]}
        (build-leave-organization-plan cfg default-team-id teams-to-delete nil)]
    {:teams-to-delete   (count deletable-team-ids)
     :teams-to-transfer teams-to-transfer-count
     :teams-to-exit     teams-to-exit-count
     :teams-to-detach   (count detach-from-organization-team-ids)}))

(def ^:private schema:leave-organization
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:default-team-id ::sm/uuid]
   [:teams-to-delete
    [:vector ::sm/uuid]]
   [:teams-to-leave
    [:vector
     [:map
      [:id ::sm/uuid]
      [:reassign-to {:optional true} ::sm/uuid]]]]])

(def ^:private schema:get-leave-organization-summary-result
  [:map
   [:teams-to-delete ::sm/int]
   [:teams-to-transfer ::sm/int]
   [:teams-to-exit ::sm/int]
   [:teams-to-detach ::sm/int]
   [:team-ids-to-delete [:vector ::sm/uuid]]
   [:transferable-teams
    [:vector
     [:map
      [:id ::sm/uuid]
      [:name ::sm/text]
      [:members
       [:vector
        [:map
         [:id ::sm/uuid]
         [:name :string]
         [:email ::sm/text]
         [:is-admin ::sm/boolean]]]]]]]
   [:member-added-at [:maybe ct/schema:inst]]
   [:organization-member-count-before ::sm/int]])

(def ^:private schema:get-leave-organization-summary
  [:map
   [:id ::sm/uuid]
   [:default-team-id ::sm/uuid]])


(defn- get-organization-teams-for-user
  [{:keys [::db/conn] :as cfg} organization-summary profile-id]
  (let [organization-team-ids (->> (:teams organization-summary)
                                   (map :id))
        ids-array    (db/create-array conn "uuid" organization-team-ids)]
    (db/exec! conn [sql:get-member-teams-info profile-id ids-array])))

(defn- calculate-valid-teams
  ([organization-teams default-team-id]
   (let [;; valid default team is the one which id is default-team-id
         valid-default-team          (d/seek #(= default-team-id (:id %)) organization-teams)

         ;; Remove your-penpot for the rest of validations
         organization-teams                   (remove #(= default-team-id (:id %)) organization-teams)

         ;; valid teams to delete are those that the user is owner, and only have one member
         valid-teams-to-delete-ids   (->> organization-teams
                                          (filter #(and (:is-owner %)
                                                        (= (:num-members %) 1)))
                                          (map :id)
                                          (into #{}))
         ;; valid teams to transfer are those that the user is owner, and have more than one member
         valid-teams-to-transfer     (->> organization-teams
                                          (filter #(and (:is-owner %)
                                                        (> (:num-members %) 1))))

         ;; valid teams to exit are those that the user isn't owner, and have more than one member
         valid-teams-to-exit         (->> organization-teams
                                          (filter #(and (not (:is-owner %))
                                                        (> (:num-members %) 1))))]
     {:valid-teams-to-delete-ids valid-teams-to-delete-ids
      :valid-teams-to-transfer valid-teams-to-transfer
      :valid-teams-to-exit valid-teams-to-exit
      :valid-default-team valid-default-team})))

(defn get-valid-teams [cfg organization-id profile-id default-team-id]
  (let [organization-summary                (nitrate/call cfg :get-organization-summary {:organization-id organization-id})
        organization-teams                  (get-organization-teams-for-user cfg organization-summary profile-id)]
    (calculate-valid-teams organization-teams default-team-id)))

(defn- assert-valid-teams [cfg profile-id organization-id default-team-id teams-to-delete teams-to-leave]
  (let [organization-summary                (nitrate/call cfg :get-organization-summary {:organization-id organization-id})
        organization-teams                  (get-organization-teams-for-user cfg organization-summary profile-id)
        {:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit
                valid-default-team]} (calculate-valid-teams organization-teams default-team-id)



        valid-teams-to-exit-ids     (->> valid-teams-to-exit (map :id) (into #{}))
        valid-teams-to-transfer-ids (->> valid-teams-to-transfer (map :id) (into #{}))
        valid-teams-to-leave-ids    (into valid-teams-to-transfer-ids valid-teams-to-exit-ids)

        valid-default-team-id?      (some? valid-default-team)



        valid-teams-to-delete?      (= valid-teams-to-delete-ids (into #{} teams-to-delete))

        ;; for every team in teams-to-leave, check that:
        ;; - if it has a reassign-to, it belongs to valid-teams-to-transfer and
        ;;   the reassign-to is a member of the team and not the current user;
        ;; - if it hasn't a reassign-to, check that it belongs to valid-teams-to-exit
        teams-by-id                 (d/index-by :id organization-teams)
        valid-teams-to-leave?       (and
                                     (= valid-teams-to-leave-ids (->> teams-to-leave (map :id) (into #{})))
                                     (every? (fn [{:keys [id reassign-to]}]
                                               (if reassign-to
                                                 (let [members (db/pgarray->set (:member-ids (get teams-by-id id)))]
                                                   (and (contains? valid-teams-to-transfer-ids id)
                                                        (not= reassign-to profile-id)
                                                        (contains? members reassign-to)))
                                                 (contains? valid-teams-to-exit-ids id)))
                                             teams-to-leave))]
    ;; the organization owner cannot leave
    (when (= (:owner-id organization-summary) profile-id)
      (ex/raise :type :validation
                :code :organization-owner-cannot-leave))

    (when (or
           (not valid-teams-to-delete?)
           (not valid-teams-to-leave?)
           (not valid-default-team-id?))
      (ex/raise :type :validation
                :code :not-valid-teams))))



(defn leave-organization
  [{:keys [::db/conn] :as cfg}
   {:keys [profile-id id name default-team-id teams-to-delete teams-to-leave skip-validation keep-default-team-requested?
           user-who-delete-member deleted-by-role]}]
  (let [organization-prefix (str "[" (d/sanitize-string name) "] ")
        {:keys [deletable-team-ids
                keep-default-team?
                detach-from-organization-team-ids]} (build-leave-organization-plan cfg default-team-id teams-to-delete keep-default-team-requested?)]

    ;; assert that the received teams are valid, checking the different constraints
    (when-not skip-validation
      (assert-valid-teams cfg profile-id id default-team-id teams-to-delete teams-to-leave))

    (nitrate/assert-membership cfg profile-id id)

    ;; delete only eligible teams (non-protected and without files)
    (doseq [id deletable-team-ids]
      (teams/delete-team cfg {:profile-id profile-id
                              :team-id id}))

    ;; leave the teams-to-leave
    (doseq [{:keys [id reassign-to]} teams-to-leave]
      (teams/leave-team cfg {:profile-id profile-id :id id :reassign-to reassign-to}))

    ;; Process organization "Personal Projects" team: keep with prefix if needed, otherwise delete.
    (when default-team-id
      (if keep-default-team?
        (db/exec! conn [sql:prefix-team-name-and-unset-default organization-prefix default-team-id])
        (teams/delete-team cfg {:profile-id profile-id
                                :team-id default-team-id})))

    ;; Detach retained owned teams from the organization in Nitrate.
    ;; Nitrate will rehome them to its fallback/default organization.
    (doseq [team-id detach-from-organization-team-ids]
      (nitrate/call cfg :remove-team-from-organization {:team-id team-id
                                                        :organization-id id}))

    ;; Api call to nitrate
    (nitrate/call cfg :remove-profile-from-organization
                  {:profile-id profile-id
                   :organization-id id
                   :user-who-delete-member user-who-delete-member
                   :deleted-by-role deleted-by-role})

    nil))


(sv/defmethod ::leave-organization
  {::rpc/auth true
   ::nitrate/sso false
   ::doc/added "2.18"
   ::sm/params schema:leave-organization
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (leave-organization cfg (assoc params
                                 :profile-id profile-id
                                 :user-who-delete-member profile-id
                                 :deleted-by-role "organization-member")))


(defn- get-transferable-teams
  "`teams-to-transfer` with their members, except `profile-id`."
  [{:keys [::db/conn]} teams-to-transfer profile-id]
  (mapv (fn [{:keys [id name]}]
          {:id      id
           :name    name
           :members (->> (teams/get-team-members conn id)
                         (remove #(= profile-id (:id %)))
                         (mapv #(select-keys % [:id :name :email :is-admin])))})
        teams-to-transfer))

;; Not SSO gated, so members can leave without SSO credentials.
(sv/defmethod ::get-leave-organization-summary
  {::rpc/auth true
   ::nitrate/sso false
   ::doc/added "2.18"
   ::sm/params schema:get-leave-organization-summary
   ::sm/result schema:get-leave-organization-summary-result
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id default-team-id]}]
  (let [{:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit
                valid-default-team]} (get-valid-teams cfg id profile-id default-team-id)
        membership              (nitrate/call cfg :get-organization-membership
                                              {:profile-id profile-id
                                               :organization-id id})
        organization-members    (nitrate/call cfg :get-organization-members
                                              {:organization-id id})
        teams-to-transfer-count (count valid-teams-to-transfer)
        teams-to-exit-count     (count valid-teams-to-exit)]
    (when-not valid-default-team
      (ex/raise :type :validation
                :code :not-valid-teams))
    (assoc
     (get-leave-organization-summary cfg default-team-id valid-teams-to-delete-ids teams-to-transfer-count teams-to-exit-count)
     :team-ids-to-delete (vec valid-teams-to-delete-ids)
     :transferable-teams (get-transferable-teams cfg valid-teams-to-transfer profile-id)
     :member-added-at (:created-at membership)
     :organization-member-count-before (count organization-members))))


(def ^:private schema:remove-team-from-organization
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:organization-name ::sm/text]])

(sv/defmethod ::remove-team-from-organization
  {::doc/added "2.18"
   ::sm/params schema:remove-team-from-organization}
  [cfg {:keys [::rpc/profile-id  team-id organization-id organization-name]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (nitrate/assert-membership cfg profile-id organization-id)
  ;; Check moveTeams permission on the source organization
  (when (contains? cf/flags :admin-console)
    (let [organization-perms (nitrate/call cfg :get-organization-permissions
                                           {:organization-id organization-id})]
      (if (nil? organization-perms)
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "Unable to verify organization permissions")
        (when-not (cto/allowed? :move-team
                                {:organization-perms organization-perms
                                 :profile-id profile-id})
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "You are not allowed to move teams that are part of this organization. If you need more information, contact the owner.")))))

  ;; Api call to nitrate
  (nitrate/call cfg :remove-team-from-organization {:team-id team-id :organization-id organization-id})

  ;; Notify connected users
  (notifications/notify-team-change cfg {:id team-id :organization {:name organization-name}} "dashboard.team-no-longer-belong-organization")
  nil)

(def ^:private sql:delete-team-external-invitations
  "DELETE FROM team_invitation
    WHERE team_id = ?
      AND email_to = ANY(?)
      AND valid_until > now()")

(def ^:private sql:get-profiles-by-emails
  "SELECT id, email
     FROM profile
    WHERE email = ANY(?)
      AND deleted_at IS NULL")

(defn- get-external-invitation-info
  "Returns info about external (non-organization-member) invitations pending for a team.
   External invitations are those sent to users who are not members of the given organization.
   Returns {:allows-anybody bool :external-emails [...]}"
  [{:keys [::db/conn] :as cfg} team-id organization-id]
  (let [organization-perms      (nitrate/call cfg :get-organization-permissions {:organization-id organization-id})
        allows-anybody (cto/allowed? :add-anybody-to-team {:organization-perms organization-perms})]
    (if allows-anybody
      {:allows-anybody true :external-emails []}
      (let [emails (map :email (noh/get-team-invitation-emails conn team-id))]
        (if (empty? emails)
          {:allows-anybody false :external-emails []}
          (let [emails-array             (db/create-array conn "text" (vec emails))
                profiles                 (db/exec! conn [sql:get-profiles-by-emails emails-array])
                organization-member-ids  (into #{} (nitrate/call cfg :get-organization-members {:organization-id organization-id}))
                member-emails            (->> profiles
                                              (filter #(contains? organization-member-ids (:id %)))
                                              (map :email)
                                              (into #{}))
                external-emails          (into [] (remove member-emails emails))]
            {:allows-anybody false :external-emails external-emails}))))))

(def ^:private schema:add-team-to-organization
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(sv/defmethod ::add-team-to-organization
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:add-team-to-organization
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id team-id organization-id]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (nitrate/assert-membership cfg profile-id organization-id)

  (when (contains? cf/flags :admin-console)
    (let [organization-member-ids-before (into #{} (nitrate/call cfg :get-organization-members {:organization-id organization-id}))
          team-with-organization         (nitrate/call cfg :get-team-organization {:team-id team-id})
          source-organization-id         (get-in team-with-organization [:organization :id])
          source-organization-perms      (when source-organization-id
                                           (nitrate/call cfg :get-organization-permissions
                                                         {:organization-id source-organization-id}))
          target-organization-perms      (nitrate/call cfg :get-organization-permissions
                                                       {:organization-id organization-id})
          target-organization-same-owner? (and (some? source-organization-perms)
                                               (some? target-organization-perms)
                                               (= (:owner-id source-organization-perms)
                                                  (:owner-id target-organization-perms)))]
      (when (nil? target-organization-perms)
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "Unable to verify organization permissions"))

      ;; Team already belongs to an organization: check move-teams on the source organization.
      (when (some? source-organization-id)
        (when (nil? source-organization-perms)
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "Unable to verify organization permissions"))
        (when-not (cto/allowed? :move-team
                                {:organization-perms source-organization-perms
                                 :profile-id profile-id
                                 :target-organization-same-owner? target-organization-same-owner?})
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "You are not allowed to move teams that are part of this organization. If you need more information, contact the owner.")))

      ;; Always check target create-teams permission (new/add and move flows).
      (when-not (cto/allowed? :create-team
                              {:organization-perms target-organization-perms
                               :profile-id profile-id})
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "You are not allowed to add teams in this organization"))

      ;; Add teammates to the organization if needed
      (let [team-members (db/query cfg :team-profile-rel {:team-id team-id})
            new-member-ids (->> team-members
                                (map :profile-id)
                                (remove #{profile-id})
                                (remove organization-member-ids-before))]
        (doseq [member-id new-member-ids]
          (teams/initialize-user-in-organization cfg member-id organization-id)))

      ;; Api call to nitrate
      (let [team (nitrate/call cfg :set-team-organization {:team-id team-id
                                                           :organization-id organization-id
                                                           :is-default false})]
        ;; Notify connected users
        (notifications/notify-team-change cfg team "dashboard.team-belong-organization"))

      ;; Delete pending invitations for users who are not members of the target organization
      (let [{:keys [allows-anybody external-emails]} (get-external-invitation-info cfg team-id organization-id)]
        (when (and (not allows-anybody) (seq external-emails))
          (let [conn         (::db/conn cfg)
                emails-array (db/create-array conn "text" external-emails)]
            (db/exec! conn [sql:delete-team-external-invitations team-id emails-array]))))

      ;; Send warnings via email if the organization has sso
      (neh/send-organization-setup-sso-emails-for-team!
       cfg organization-id team-id organization-member-ids-before)))

  nil)

(def ^:private schema:check-organization-members-params
  [:map {:title "CheckOrganizationMembersParams"}
   [:organization-id ::sm/uuid]
   [:emails [:vector ::sm/email]]])

(sv/defmethod ::check-organization-members
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:check-organization-members-params
   ::sm/result [:map-of :string :boolean]
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id organization-id emails]}]
  (or (when (contains? cf/flags :admin-console)
        (nitrate/assert-membership cfg profile-id organization-id)
        (let [emails-array   (db/create-array conn "text" emails)
              profiles       (db/exec! conn [sql:get-profiles-by-emails emails-array])
              email->id      (into {} (map (fn [p] [(:email p) (:id p)])) profiles)
              organization-member-ids (into #{} (nitrate/call cfg :get-organization-members {:organization-id organization-id}))]
          (into {}
                (map (fn [email]
                       (let [pid (get email->id email)]
                         [email (boolean (and pid (contains? organization-member-ids pid)))])))
                emails)))
      {}))

(def ^:private schema:all-organization-members-in-team-params
  [:map {:title "CheckOrganizationMembersInTeamParams"}
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(sv/defmethod ::all-organization-members-in-team
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:all-organization-members-in-team-params
   ::sm/result ::sm/boolean}
  [cfg {:keys [::rpc/profile-id team-id organization-id]}]
  (if (contains? cf/flags :admin-console)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))
      (nitrate/assert-membership cfg profile-id organization-id)
      (let [organization-members     (nitrate/call cfg :get-organization-members {:organization-id organization-id})
            organization-member-ids  (into #{} organization-members)
            team-members    (db/query cfg :team-profile-rel {:team-id team-id})
            team-member-ids (into #{} (map :profile-id team-members))]
        (every? #(contains? team-member-ids %) organization-member-ids)))
    false))

(def ^:private schema:all-team-members-in-organizations-params
  [:map {:title "CheckTeamMembersInOrganizationsParams"}
   [:team-id ::sm/uuid]
   [:organization-ids [:vector ::sm/uuid]]])

(sv/defmethod ::all-team-members-in-organizations
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:all-team-members-in-organizations-params
   ::sm/result [:map-of ::sm/uuid ::sm/boolean]}
  [cfg {:keys [::rpc/profile-id team-id organization-ids]}]
  (if (contains? cf/flags :admin-console)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (let [team-members    (db/query cfg :team-profile-rel {:team-id team-id})
            team-member-ids (into #{} (map :profile-id team-members))]
        ;; Validate requester membership in all organizations before fetching members.
        (run! #(nitrate/assert-membership cfg profile-id %) organization-ids)

        (into {}
              (map (fn [organization-id]
                     (let [organization-members    (nitrate/call cfg :get-organization-members {:organization-id organization-id})
                           organization-member-ids (into #{} organization-members)]
                       [organization-id
                        (every? #(contains? organization-member-ids %) team-member-ids)])))
              organization-ids)))
    {}))

(def ^:private schema:check-team-external-invitations-params
  [:map {:title "CheckTeamExternalInvitationsParams"}
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(def ^:private schema:check-team-external-invitations-result
  [:map {:title "CheckTeamExternalInvitationsResult"}
   [:has-external-invitations ::sm/boolean]
   [:allows-anybody ::sm/boolean]])

(sv/defmethod ::check-team-external-invitations
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:check-team-external-invitations-params
   ::sm/result schema:check-team-external-invitations-result
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id team-id organization-id]}]
  (if (contains? cf/flags :admin-console)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))
      (nitrate/assert-membership cfg profile-id organization-id)
      (let [{:keys [allows-anybody external-emails]} (get-external-invitation-info cfg team-id organization-id)]
        {:has-external-invitations (boolean (seq external-emails))
         :allows-anybody allows-anybody}))
    {:has-external-invitations false
     :allows-anybody false}))


(def ^:private schema:check-nitrate-sso
  [:and
   [:map {:title "CheckNitrateSsoParams"}
    [:team-id {:optional true} ::sm/uuid]
    [:organization-id {:optional true} ::sm/uuid]
    [:url ::sm/uri]]
   [::sm/contains-any #{:team-id :organization-id}]])

(sv/defmethod ::check-nitrate-sso
  "Check if a user needs to login into the organization SSO.
  Accepts either team-id (to look up the organization via the team) or organization-id directly.
  Returns {:authorized true :reason :sso-satisfied} when SSO is not active or the
  session already holds a valid entry for the organization, and
  {:authorized true :reason :no-team-access} when the gate was skipped because the
  user cannot access the team; the reason lets the client tell a usable session
  apart from a plain permission failure.
  Returns {:authorized false :redirect-uri <url>} when SSO is active;
  the client must redirect there. The OIDC provider itself handles
  re-authentication transparently if the user already has an active SSO session.
  A nil :redirect-uri means SSO is required but the provider is not usable."
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:check-nitrate-sso
   ::nitrate/sso false}
  [cfg {:keys [::rpc/profile-id team-id organization-id url] :as params}]
  (if (contains? cf/flags :admin-console)
    (if (and team-id
             (not (teams/has-read-permissions? cfg profile-id team-id)))
      ;; Let the destination RPC enforce its own permissions. Starting SSO before
      ;; access is established sends unrelated users through the organization's IdP.
      {:authorized true :reason :no-team-access}
      (let [request                  (rph/get-request params)
            {:keys [authorized sso]} (nitrate/sso-session-authorized? cfg organization-id team-id request)]
        (if authorized
          {:authorized true :reason :sso-satisfied}
          (if (oidc/organization-sso-discovery-uri sso)
            (try
              (let [redirect-uri (oidc/build-organization-sso-auth-redirect-uri
                                  cfg sso
                                  :dest-url url
                                  :organization-id organization-id)
                    organization-id (or organization-id (:organization-id sso))]
                (oidc/submit-organization-sso-auth-started-event
                 cfg request profile-id organization-id)
                {:authorized false :redirect-uri redirect-uri})
              (catch Throwable cause
                (oidc/submit-organization-sso-auth-failed-event
                 cfg request profile-id (or organization-id (:organization-id sso)) cause)
                (throw cause)))
            {:authorized false
             :redirect-uri nil}))))
    {:authorized true :reason :sso-satisfied}))

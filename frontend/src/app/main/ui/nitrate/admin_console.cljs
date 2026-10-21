;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.nitrate.admin-console
  "Local admin console of the self-hosted fork: the organizations owned
  by the current profile, with their OIDC single sign-on, permissions,
  teams, members and audit log."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; NOTE: every handler below that reads component state lists that
;; state in `mf/deps`. `mf/use-state` hands out a new object per value,
;; so a callback memoized without deps keeps reading the first render's
;; values (an empty name, no selected organization...).

(def ^:private empty-sso
  {:active false :issuer "" :client-id "" :client-secret ""})

(def ^:private default-permissions
  {:create-teams "any"
   :delete-teams "onlyOwners"
   :move-teams "always"
   :send-invitations "ownersAndAdmins"
   :new-team-members "anyone"})

(def ^:private permission-fields
  [{:key :create-teams
    :label "Création d’équipes"
    :options [{:id "any" :label "Tous les membres"}
              {:id "onlyMe" :label "Propriétaire de l’organisation uniquement"}]}
   {:key :delete-teams
    :label "Suppression d’équipes"
    :options [{:id "onlyOwners" :label "Propriétaires d’équipe et d’organisation"}
              {:id "never" :label "Propriétaire de l’organisation uniquement"}]}
   {:key :move-teams
    :label "Déplacement d’équipes"
    :options [{:id "always" :label "Autorisé"}
              {:id "myOrganizations" :label "Entre mes organisations"}
              {:id "never" :label "Désactivé"}]}
   {:key :send-invitations
    :label "Envoi d’invitations"
    :options [{:id "ownersAndAdmins" :label "Propriétaires et admins"}
              {:id "owners" :label "Propriétaires uniquement"}]}
   {:key :new-team-members
    :label "Nouveaux membres dans les équipes"
    :options [{:id "anyone" :label "Tout le monde"}
              {:id "members" :label "Membres de l’organisation uniquement"}]}])

(defn- failure
  "Error notice for a failed call: `message`, followed by the reason
  the backend gave when there is one."
  [message cause]
  (let [hint (-> cause ex-data :hint)]
    {:level :error
     :text (if (str/blank? hint)
             message
             (dm/str message " (" hint ")"))}))

(defn- success
  [message]
  {:level :success :text message})

(defn- plural
  [n singular plural]
  (dm/str n " " (if (= 1 n) singular plural)))

(defn- event-title
  [event-name]
  (str/capital (str/replace (dm/str event-name) "-" " ")))

;; --- Shared pieces

(mf/defc form-notice*
  {::mf/private true}
  [{:keys [notice]}]
  (when-let [{:keys [level text]} notice]
    [:> context-notification* {:level level :class (stl/css :notice)}
     text]))

(mf/defc card*
  {::mf/private true}
  [{:keys [title description badge class children]}]
  [:section {:class [(stl/css :card) class]}
   [:header {:class (stl/css :card-header)}
    [:> heading* {:level 2 :typography t/headline-medium :class (stl/css :card-title)}
     title]
    badge]
   (when description
     [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
      description])
   children])

;; --- Organizations column

(mf/defc organization-list*
  {::mf/private true}
  [{:keys [organizations selected-id on-select]}]
  (let [on-click
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "id")
                        (uuid/parse))]
             (on-select id))))]
    (cond
      (nil? organizations)
      [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)}
       "Chargement…"]

      (empty? organizations)
      [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)}
       "Aucune organisation pour le moment."]

      :else
      [:ul {:class (stl/css :organization-list)}
       (for [{:keys [id name member-count team-count]} organizations]
         [:li {:key (dm/str id)}
          [:button {:type "button"
                    :class (stl/css-case :organization-item true
                                         :selected (= id selected-id))
                    :aria-current (when (= id selected-id) "true")
                    :data-id (dm/str id)
                    :on-click on-click}
           [:span {:class (stl/css :organization-name)} name]
           [:span {:class (stl/css :organization-meta)}
            (dm/str (plural member-count "membre" "membres")
                    " · "
                    (plural team-count "équipe" "équipes"))]]])])))

(mf/defc create-organization-form*
  {::mf/private true}
  [{:keys [on-created autofocus]}]
  (let [name*    (mf/use-state "")
        name     (deref name*)
        pending* (mf/use-state false)
        pending  (deref pending*)
        notice*  (mf/use-state nil)
        notice   (deref notice*)

        on-name-change
        (mf/use-fn
         (fn [event]
           (reset! name* (dom/get-target-val event))))

        on-submit
        (mf/use-fn
         (mf/deps name pending on-created)
         (fn [event]
           (dom/prevent-default event)
           (let [name (str/trim name)]
             (when (and (not pending) (not (str/blank? name)))
               (reset! pending* true)
               (reset! notice* nil)
               (->> (rp/cmd! :create-organization {:name name})
                    (rx/subs! (fn [organization]
                                (reset! pending* false)
                                (reset! name* "")
                                (on-created organization))
                              (fn [cause]
                                (reset! pending* false)
                                (reset! notice* (failure "Impossible de créer l’organisation." cause)))))))))]

    [:form {:class (stl/css :form) :on-submit on-submit}
     [:> input* {:label "Nom de l’organisation"
                 :placeholder "Mon organisation"
                 :variant "comfortable"
                 :value name
                 :max-length 120
                 :required true
                 :auto-focus autofocus
                 :on-change on-name-change}]
     [:> form-notice* {:notice notice}]
     [:div {:class (stl/css :form-actions)}
      [:> button* {:type "submit"
                   :variant "primary"
                   :icon i/add
                   :disabled (or pending (str/blank? name))}
       (if pending "Création…" "Créer l’organisation")]]]))

;; --- Organization sections

(mf/defc general-section*
  {::mf/private true}
  [{:keys [organization on-changed]}]
  (let [organization-id (:id organization)
        current-name    (:name organization)
        name*           (mf/use-state current-name)
        name            (deref name*)
        pending*        (mf/use-state false)
        pending         (deref pending*)
        notice*         (mf/use-state nil)
        notice          (deref notice*)

        on-name-change
        (mf/use-fn
         (fn [event]
           (reset! name* (dom/get-target-val event))))

        on-submit
        (mf/use-fn
         (mf/deps organization-id name pending on-changed)
         (fn [event]
           (dom/prevent-default event)
           (let [name (str/trim name)]
             (when (and (not pending) (not (str/blank? name)))
               (reset! pending* true)
               (reset! notice* nil)
               (->> (rp/cmd! :rename-organization {:organization-id organization-id :name name})
                    (rx/subs! (fn [_]
                                (reset! pending* false)
                                (reset! notice* (success "Organisation renommée."))
                                (on-changed))
                              (fn [cause]
                                (reset! pending* false)
                                (reset! notice* (failure "Impossible de renommer l’organisation." cause)))))))))]

    [:> card* {:title "Général"}
     [:form {:class (stl/css :form) :on-submit on-submit}
      [:> input* {:label "Nom de l’organisation"
                  :variant "comfortable"
                  :value name
                  :max-length 120
                  :required true
                  :on-change on-name-change}]
      [:> form-notice* {:notice notice}]
      [:div {:class (stl/css :form-actions)}
       [:> button* {:type "submit"
                    :variant "secondary"
                    :disabled (or pending
                                  (str/blank? name)
                                  (= (str/trim name) current-name))}
        "Renommer"]]]]))

(mf/defc sso-section*
  {::mf/private true}
  [{:keys [organization-id on-changed]}]
  (let [form*    (mf/use-state nil)
        form     (deref form*)
        active*  (mf/use-state nil)
        active   (deref active*)
        pending* (mf/use-state false)
        pending  (deref pending*)
        notice*  (mf/use-state nil)
        notice   (deref notice*)

        callback-uri
        (dm/str cf/public-uri "api/auth/oidc/callback")

        on-active-change
        (mf/use-fn
         (fn [checked]
           (swap! form* assoc :active checked)))

        on-issuer-change
        (mf/use-fn
         (fn [event]
           (swap! form* assoc :issuer (dom/get-target-val event))))

        on-client-id-change
        (mf/use-fn
         (fn [event]
           (swap! form* assoc :client-id (dom/get-target-val event))))

        on-client-secret-change
        (mf/use-fn
         (fn [event]
           (swap! form* assoc :client-secret (dom/get-target-val event))))

        on-submit
        (mf/use-fn
         (mf/deps organization-id form pending on-changed)
         (fn [event]
           (dom/prevent-default event)
           (when (and (some? form) (not pending))
             (let [{:keys [active issuer client-id client-secret]} form
                   active    (boolean active)
                   issuer    (str/trim issuer)
                   client-id (str/trim client-id)
                   ;; Blank values are left out: the backend keeps the
                   ;; stored ones, the secret in particular.
                   params    (cond-> {:organization-id organization-id
                                      :active active
                                      :provider "oidc"}
                               (not (str/blank? issuer))        (assoc :issuer issuer)
                               (not (str/blank? client-id))     (assoc :client-id client-id)
                               (not (str/blank? client-secret)) (assoc :client-secret client-secret))]
               (reset! pending* true)
               (reset! notice* {:level :info
                                :text (if active
                                        "Vérification auprès du fournisseur OIDC…"
                                        "Enregistrement…")})
               (->> (rp/cmd! :set-organization-sso-settings params)
                    (rx/subs! (fn [_]
                                (reset! pending* false)
                                (reset! active* active)
                                (swap! form* assoc :client-secret "")
                                (reset! notice* (success (if active
                                                           "SSO activé : le fournisseur a validé la configuration."
                                                           "Configuration enregistrée, SSO désactivé.")))
                                (on-changed))
                              (fn [cause]
                                (reset! pending* false)
                                (reset! notice* (failure "Configuration refusée. Vérifiez l’issuer, le client ID et le secret."
                                                         cause)))))))))]

    (mf/with-effect [organization-id]
      (let [sub (->> (rp/cmd! :get-organization-sso-settings {:organization-id organization-id})
                     (rx/subs! (fn [settings]
                                 (let [settings (merge empty-sso
                                                       (d/without-nils
                                                        (select-keys settings [:active :issuer :client-id])))]
                                   (reset! active* (:active settings))
                                   (reset! form* settings)))
                               (fn [cause]
                                 (reset! form* empty-sso)
                                 (reset! notice* (failure "Impossible de charger la configuration SSO." cause)))))]
        #(rx/dispose! sub)))

    [:> card* {:title "Authentification unique (OIDC)"
               :description "Une fois le SSO actif, l’accès aux équipes de l’organisation passe par votre fournisseur OIDC, propriétaire compris. Cette console reste accessible."
               :badge (when (some? active)
                        (mf/html
                         [:span {:class (stl/css-case :badge true :badge-on active)}
                          (if active "Actif" "Inactif")]))}
     (if (nil? form)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Chargement…"]
       [:form {:class (stl/css :form) :on-submit on-submit}
        [:> switch* {:label "Activer le SSO pour cette organisation"
                     :default-checked (boolean (:active form))
                     :on-change on-active-change}]
        [:> input* {:label "Issuer OIDC"
                    :variant "comfortable"
                    :type "url"
                    :placeholder "https://sso.example.com/realms/mon-realm"
                    :hint-message "L’URL de l’issuer, sans /.well-known/openid-configuration."
                    :hint-type "hint"
                    :value (:issuer form)
                    :on-change on-issuer-change}]
        [:> input* {:label "Client ID"
                    :variant "comfortable"
                    :value (:client-id form)
                    :on-change on-client-id-change}]
        [:> input* {:label "Client secret"
                    :variant "comfortable"
                    :type "password"
                    :auto-complete "new-password"
                    :hint-message "Laissez vide pour garder le secret enregistré."
                    :hint-type "hint"
                    :value (:client-secret form)
                    :on-change on-client-secret-change}]
        [:div {:class (stl/css :callout)}
         [:> text* {:as "p" :typography t/body-small}
          "URL de redirection à autoriser chez le fournisseur :"]
         [:code {:class (stl/css :code)} callback-uri]
         [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
          "Chaque membre se connecte avec un compte Penpot existant : le fournisseur doit renvoyer la même adresse e-mail, marquée comme vérifiée."]]
        [:> form-notice* {:notice notice}]
        [:div {:class (stl/css :form-actions)}
         [:> button* {:type "submit" :variant "primary" :disabled pending}
          (if pending "Vérification…" "Valider et enregistrer")]]])]))

(mf/defc permissions-section*
  {::mf/private true}
  [{:keys [organization-id on-changed]}]
  (let [permissions* (mf/use-state nil)
        permissions  (deref permissions*)
        pending*     (mf/use-state false)
        pending      (deref pending*)
        notice*      (mf/use-state nil)
        notice       (deref notice*)

        on-field-change
        (mf/with-memo []
          (into {}
                (map (fn [{:keys [key]}]
                       [key (fn [value]
                              (swap! permissions* assoc key value))]))
                permission-fields))

        on-submit
        (mf/use-fn
         (mf/deps organization-id permissions pending on-changed)
         (fn [event]
           (dom/prevent-default event)
           (when (and (some? permissions) (not pending))
             (reset! pending* true)
             (reset! notice* nil)
             (->> (rp/cmd! :set-organization-permissions-settings
                           {:organization-id organization-id
                            :permissions permissions})
                  (rx/subs! (fn [saved]
                              (reset! pending* false)
                              (reset! permissions* (merge default-permissions saved))
                              (reset! notice* (success "Permissions enregistrées."))
                              (on-changed))
                            (fn [cause]
                              (reset! pending* false)
                              (reset! notice* (failure "Impossible d’enregistrer les permissions." cause))))))))]

    (mf/with-effect [organization-id]
      (let [sub (->> (rp/cmd! :get-organization-permissions-settings {:organization-id organization-id})
                     (rx/subs! (fn [settings]
                                 (reset! permissions* (merge default-permissions settings)))
                               (fn [cause]
                                 (reset! permissions* default-permissions)
                                 (reset! notice* (failure "Impossible de charger les permissions." cause)))))]
        #(rx/dispose! sub)))

    [:> card* {:title "Permissions"
               :description "Ces règles s’appliquent aux équipes rattachées à l’organisation."}
     (if (nil? permissions)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Chargement…"]
       [:form {:class (stl/css :form) :on-submit on-submit}
        [:div {:class (stl/css :fields-grid)}
         (for [{:keys [key label options]} permission-fields]
           [:div {:key (name key) :class (stl/css :field)}
            [:> text* {:as "span" :typography t/body-small :class (stl/css :field-label)} label]
            [:> select* {:options options
                         :default-selected (get permissions key)
                         :on-change (get on-field-change key)}]])]
        [:> form-notice* {:notice notice}]
        [:div {:class (stl/css :form-actions)}
         [:> button* {:type "submit" :variant "secondary" :disabled pending}
          "Enregistrer les permissions"]]])]))

(mf/defc teams-section*
  {::mf/private true}
  [{:keys [organization-id teams on-changed]}]
  (let [name*    (mf/use-state "")
        name     (deref name*)
        pending* (mf/use-state false)
        pending  (deref pending*)
        notice*  (mf/use-state nil)
        notice   (deref notice*)

        on-name-change
        (mf/use-fn
         (fn [event]
           (reset! name* (dom/get-target-val event))))

        on-open-team
        (mf/use-fn
         (fn [event]
           (let [team-id (-> (dom/get-current-target event)
                             (dom/get-data "id")
                             (uuid/parse))]
             (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))))

        on-submit
        (mf/use-fn
         (mf/deps organization-id name pending on-changed)
         (fn [event]
           (dom/prevent-default event)
           (let [name (str/trim name)]
             (when (and (not pending) (not (str/blank? name)))
               (reset! pending* true)
               (reset! notice* nil)
               (->> (rp/cmd! :create-organization-team {:organization-id organization-id :name name})
                    (rx/subs! (fn [team]
                                (reset! pending* false)
                                (reset! name* "")
                                (reset! notice* (success (dm/str "Équipe « " (:name team) " » créée.")))
                                (on-changed))
                              (fn [cause]
                                (reset! pending* false)
                                (reset! notice* (failure "Impossible de créer l’équipe." cause)))))))))]

    [:> card* {:title "Équipes"}
     (cond
       (nil? teams)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Chargement…"]

       (empty? teams)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Aucune équipe rattachée."]

       :else
       [:ul {:class (stl/css :rows)}
        (for [{:keys [id name member-count]} teams]
          [:li {:key (dm/str id) :class (stl/css :row)}
           [:div {:class (stl/css :row-text)}
            [:span {:class (stl/css :row-title)} name]
            [:span {:class (stl/css :row-meta)} (plural member-count "membre" "membres")]]
           [:> button* {:variant "ghost"
                        :icon i/arrow-up-right
                        :data-id (dm/str id)
                        :on-click on-open-team}
            "Ouvrir"]])])

     [:form {:class (stl/css :inline-form) :on-submit on-submit}
      [:> input* {:label "Nouvelle équipe"
                  :variant "comfortable"
                  :placeholder "Nom de l’équipe"
                  :class (stl/css :inline-input)
                  :value name
                  :max-length 120
                  :required true
                  :on-change on-name-change}]
      [:> button* {:type "submit"
                   :variant "secondary"
                   :icon i/add
                   :disabled (or pending (str/blank? name))}
       "Créer"]]
     [:> form-notice* {:notice notice}]]))

(mf/defc members-section*
  {::mf/private true}
  [{:keys [organization-id owner-id members on-changed]}]
  (let [pending* (mf/use-state false)
        pending  (deref pending*)
        notice*  (mf/use-state nil)
        notice   (deref notice*)

        on-remove
        (mf/use-fn
         (mf/deps organization-id pending on-changed)
         (fn [event]
           (let [member-id (-> (dom/get-current-target event)
                               (dom/get-data "id")
                               (uuid/parse))]
             (when (and (not pending)
                        (js/confirm "Retirer ce membre de l’organisation ? Ses équipes et leurs fichiers restent en place, l’organisation en reprend la propriété."))
               (reset! pending* true)
               (reset! notice* nil)
               (->> (rp/cmd! :remove-organization-member {:organization-id organization-id
                                                          :member-id member-id})
                    (rx/subs! (fn [_]
                                (reset! pending* false)
                                (reset! notice* (success "Membre retiré de l’organisation."))
                                (on-changed))
                              (fn [cause]
                                (reset! pending* false)
                                (reset! notice* (failure "Impossible de retirer ce membre." cause)))))))))]

    [:> card* {:title "Membres"}
     (cond
       (nil? members)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Chargement…"]

       (empty? members)
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Aucun membre."]

       :else
       [:ul {:class (stl/css :rows)}
        (for [{:keys [id name email]} members]
          [:li {:key (dm/str id) :class (stl/css :row)}
           [:div {:class (stl/css :row-text)}
            [:span {:class (stl/css :row-title)} (or name email)]
            (when name
              [:span {:class (stl/css :row-meta)} email])]
           (if (= id owner-id)
             [:span {:class (stl/css :badge)} "Propriétaire"]
             [:> button* {:variant "ghost"
                          :icon i/remove
                          :data-id (dm/str id)
                          :disabled pending
                          :on-click on-remove}
              "Retirer"])])])
     [:> form-notice* {:notice notice}]]))

(mf/defc audit-section*
  {::mf/private true}
  [{:keys [events]}]
  [:> card* {:title "Journal d’audit"
             :description "Les 100 derniers événements de l’organisation."}
   (cond
     (nil? events)
     [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Chargement…"]

     (empty? events)
     [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)} "Aucun événement récent."]

     :else
     [:ol {:class (stl/css :rows)}
      (for [{:keys [id event-name profile-id occurred-at event]} events]
        [:li {:key (dm/str id) :class (stl/css :audit-row)}
         [:div {:class (stl/css :row-text)}
          [:span {:class (stl/css :row-title)} (event-title event-name)]
          [:span {:class (stl/css :row-meta)}
           (dm/str (ct/format-inst occurred-at "dd/MM/yyyy HH:mm")
                   (when profile-id (dm/str " · profil " profile-id)))]]
         [:details {:class (stl/css :audit-details)}
          [:summary "Détails"]
          [:pre {:class (stl/css :code-block)}
           (pr-str (select-keys event [:props :context :source]))]]])])])

(mf/defc danger-section*
  {::mf/private true}
  [{:keys [organization-id on-deleted]}]
  (let [pending* (mf/use-state false)
        pending  (deref pending*)
        notice*  (mf/use-state nil)
        notice   (deref notice*)

        on-delete
        (mf/use-fn
         (mf/deps organization-id pending on-deleted)
         (fn [_]
           (when (and (not pending)
                      (js/confirm "Supprimer cette organisation ? Ses réglages et son journal d’audit seront effacés. Ses équipes doivent d’abord être déplacées ou supprimées."))
             (reset! pending* true)
             (reset! notice* nil)
             (->> (rp/cmd! :delete-organization {:organization-id organization-id})
                  (rx/subs! (fn [_]
                              (reset! pending* false)
                              (on-deleted))
                            (fn [cause]
                              (reset! pending* false)
                              (reset! notice* (failure "Impossible de supprimer l’organisation." cause))))))))]

    [:> card* {:title "Zone de danger"
               :class (stl/css :danger)
               :description "La suppression n’est possible que pour une organisation sans équipe."}
     [:> form-notice* {:notice notice}]
     [:div {:class (stl/css :form-actions)}
      [:> button* {:variant "destructive" :icon i/delete :disabled pending :on-click on-delete}
       "Supprimer l’organisation"]]]))

(mf/defc organization-panel*
  {::mf/private true}
  [{:keys [organization on-changed on-deleted]}]
  (let [organization-id (:id organization)
        summary*        (mf/use-state nil)
        summary         (deref summary*)
        audit*          (mf/use-state nil)
        audit           (deref audit*)

        load-summary
        (mf/use-fn
         (mf/deps organization-id)
         (fn []
           (->> (rp/cmd! :get-organization-admin-summary {:organization-id organization-id})
                (rx/subs! #(reset! summary* %)
                          (fn [_] (reset! summary* {:teams [] :members []}))))))

        load-audit
        (mf/use-fn
         (mf/deps organization-id)
         (fn []
           (->> (rp/cmd! :get-organization-audit-log {:organization-id organization-id})
                (rx/subs! #(reset! audit* %)
                          (fn [_] (reset! audit* []))))))

        refresh
        (mf/use-fn
         (mf/deps load-summary load-audit on-changed)
         (fn []
           (load-summary)
           (load-audit)
           (on-changed)))]

    (mf/with-effect [load-summary load-audit]
      (let [subs [(load-summary) (load-audit)]]
        #(run! rx/dispose! subs)))

    [:div {:class (stl/css :panel)}
     [:header {:class (stl/css :panel-header)}
      [:> heading* {:level 2 :typography t/title-medium :class (stl/css :panel-title)}
       (:name organization)]
      [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
       (dm/str "Slug : " (:slug organization) " · Identifiant : " organization-id)]]
     [:> general-section* {:organization organization :on-changed refresh}]
     [:> sso-section* {:organization-id organization-id :on-changed refresh}]
     [:> permissions-section* {:organization-id organization-id :on-changed refresh}]
     [:> teams-section* {:organization-id organization-id
                         :teams (:teams summary)
                         :on-changed refresh}]
     [:> members-section* {:organization-id organization-id
                           :owner-id (:owner-id organization)
                           :members (:members summary)
                           :on-changed refresh}]
     [:> audit-section* {:events audit}]
     [:> danger-section* {:organization-id organization-id :on-deleted on-deleted}]]))

;; --- Page

(mf/defc admin-console-page*
  [{:keys [route]}]
  (let [query-params   (:query-params route)
        requested-id   (some-> query-params :organization-id uuid/parse*)
        create?        (= "create-organization" (:action query-params))

        organizations* (mf/use-state nil)
        organizations  (deref organizations*)
        selected-id*   (mf/use-state requested-id)
        selected-id    (deref selected-id*)
        notice*        (mf/use-state nil)
        notice         (deref notice*)

        selected
        (mf/with-memo [organizations selected-id]
          (d/seek #(= selected-id (:id %)) organizations))

        load-organizations
        (mf/use-fn
         (fn []
           (->> (rp/cmd! :get-owned-organizations {})
                (rx/subs! (fn [organizations]
                            (reset! notice* nil)
                            (reset! organizations* organizations)
                            (swap! selected-id*
                                   (fn [id]
                                     (if (some #(= id (:id %)) organizations)
                                       id
                                       (:id (first organizations))))))
                          (fn [cause]
                            (if (= :authentication (-> cause ex-data :type))
                              (st/emit! (rt/nav :auth-login))
                              (do
                                (reset! organizations* [])
                                (reset! notice* (failure "Impossible de charger vos organisations." cause)))))))))

        ;; The selection goes to the URL too, so that a reload or a
        ;; shared link opens the same organization.
        on-select
        (mf/use-fn
         (fn [id]
           (reset! selected-id* id)
           (st/emit! (rt/nav :enterprise-admin
                             {:organization-id (dm/str id)}
                             ::rt/replace true))))

        on-created
        (mf/use-fn
         (mf/deps on-select load-organizations)
         (fn [organization]
           (on-select (:id organization))
           (load-organizations)))

        on-deleted
        (mf/use-fn
         (mf/deps load-organizations)
         (fn []
           (reset! selected-id* nil)
           (reset! notice* (success "Organisation supprimée."))
           (load-organizations)))

        go-to-dashboard
        (mf/use-fn
         (fn []
           (st/emit! (dcm/go-to-dashboard-recent :team-id :default))))]

    (mf/with-effect [load-organizations]
      (let [sub (load-organizations)]
        #(rx/dispose! sub)))

    [:main {:class (stl/css :console)}
     [:div {:class (stl/css :console-inner)}
      [:header {:class (stl/css :header)}
       [:div {:class (stl/css :header-text)}
        [:> text* {:as "p" :typography t/body-small :class (stl/css :eyebrow)}
         "Administration"]
        [:> heading* {:level 1 :typography t/title-large :class (stl/css :title)}
         "Console d’administration"]
        [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)}
         "Vos organisations, leur connexion SSO et leurs permissions."]]
       [:> button* {:variant "secondary" :icon i/arrow-left :on-click go-to-dashboard}
        "Tableau de bord"]]

      [:> form-notice* {:notice notice}]

      [:div {:class (stl/css :layout)}
       [:aside {:class (stl/css :sidebar)}
        [:> card* {:title "Organisations"}
         [:> organization-list* {:organizations organizations
                                 :selected-id selected-id
                                 :on-select on-select}]]
        [:> card* {:title "Nouvelle organisation"
                   :description "Une équipe « Personal Projects » est créée avec elle."}
         [:> create-organization-form* {:on-created on-created
                                        :autofocus create?}]]]

       [:div {:class (stl/css :content)}
        (if selected
          [:> organization-panel* {:key (dm/str (:id selected))
                                   :organization selected
                                   :on-changed load-organizations
                                   :on-deleted on-deleted}]
          [:section {:class (stl/css :card :empty)}
           [:> text* {:as "p" :typography t/body-medium :class (stl/css :muted)}
            (cond
              (nil? organizations) "Chargement…"
              (seq organizations) "Sélectionnez une organisation."
              :else "Créez une organisation pour configurer son SSO.")]])]]]]))

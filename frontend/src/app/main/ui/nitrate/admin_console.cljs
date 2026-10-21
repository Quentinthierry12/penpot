;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.nitrate.admin-console
  (:require
   [app.config :as cf]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [clojure.string :as str]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private page-style
  {:max-width "1080px" :margin "48px auto" :padding "0 24px"
   :font-family "Inter, sans-serif" :color "#20212a"})

(def ^:private card-style
  {:border "1px solid #dedfe5" :border-radius "12px" :padding "24px"
   :background "#fff" :margin-bottom "16px"})

(def ^:private field-style
  {:display "block" :width "100%" :box-sizing "border-box" :padding "10px 12px"
   :margin "6px 0 14px" :border "1px solid #c9cbd3" :border-radius "6px"})

(defn- input-field
  [label value type on-change]
  [:label {:style {:display "block" :font-size "14px" :font-weight 600}}
   label
   [:input {:style field-style
            :type type
            :value (or value "")
            :on-change on-change}]] )

(defn- permission-field
  [label key options permissions*]
  [:label {:style {:display "block" :font-size "14px" :font-weight 600}}
   label
   [:select {:style field-style
             :value (get @permissions* key)
             :on-change #(swap! permissions* assoc key (.. % -target -value))}
    (for [[value title] options]
      [:option {:key value :value value} title])]])

(mf/defc admin-console-page*
  [{:keys [route]}]
  (let [organizations* (mf/use-state [])
        selected-id*   (mf/use-state (some-> route :query-params :organization-id uuid/parse*))
        create-name*   (mf/use-state "")
        rename-name*   (mf/use-state "")
        create-team-name* (mf/use-state "")
        sso*           (mf/use-state {:active false :provider "oidc" :client-id "" :client-secret "" :issuer ""})
        permissions*   (mf/use-state {:create-teams "any" :delete-teams "onlyOwners" :move-teams "always" :send-invitations "ownersAndAdmins" :new-team-members "anyone"})
        audit*         (mf/use-state [])
        administration* (mf/use-state {:teams [] :members []})
        message*       (mf/use-state nil)
        loading*       (mf/use-state false)
        selected       (some #(when (= (:id %) @selected-id*) %) @organizations*)
        load-organizations
        (mf/use-fn
         (fn []
           (reset! loading* true)
           (->> (rp/cmd! :get-owned-organizations {})
                (rx/subs!
                 (fn [organizations]
                   (reset! organizations* organizations)
                   (when (and (seq organizations) (nil? @selected-id*))
                     (reset! selected-id* (:id (first organizations))))
                   (reset! loading* false))
                 (fn [_]
                   (reset! message* "Impossible de charger les organisations.")
                   (reset! loading* false))))))
        select-organization
        (mf/use-fn
         (fn [organization-id]
           (reset! selected-id* organization-id)
           (reset! sso* {:active false :provider "oidc" :client-id "" :client-secret "" :issuer ""})
           (reset! permissions* {:create-teams "any" :delete-teams "onlyOwners" :move-teams "always" :send-invitations "ownersAndAdmins" :new-team-members "anyone"})
           (reset! audit* [])
           (reset! administration* {:teams [] :members []})
           (->> (rp/cmd! :get-organization-sso-settings {:organization-id organization-id})
                (rx/subs! (fn [settings]
                            (when settings
                              (reset! sso* (merge @sso* settings))))))
           (->> (rp/cmd! :get-organization-permissions-settings {:organization-id organization-id})
                (rx/subs! (fn [settings]
                            (when settings (reset! permissions* settings)))))
           (->> (rp/cmd! :get-organization-audit-log {:organization-id organization-id})
                (rx/subs! (fn [events] (reset! audit* events))))
           (->> (rp/cmd! :get-organization-admin-summary {:organization-id organization-id})
                (rx/subs! (fn [summary] (reset! administration* summary))))))
        create-organization
        (mf/use-fn
         (fn [event]
           (.preventDefault event)
           (when-not (empty? (str/trim @create-name*))
             (->> (rp/cmd! :create-organization {:name @create-name*})
                  (rx/subs! (fn [organization]
                              (reset! create-name* "")
                              (reset! selected-id* (:id organization))
                              (load-organizations)))))))
        save-sso
        (mf/use-fn
         (fn [event]
           (.preventDefault event)
           (when @selected-id*
             (let [params (-> @sso*
                              (select-keys [:active :provider :client-id :issuer])
                              (assoc :organization-id @selected-id*))
                   params (cond-> params
                            (seq (:client-secret @sso*)) (assoc :client-secret (:client-secret @sso*)))]
               (reset! message* "Validation et enregistrement de la configuration OIDC…")
               (->> (rp/cmd! :set-organization-sso-settings params)
                    (rx/subs! (fn [_]
                                (reset! message* "Configuration SSO enregistrée.")
                                (reset! sso* (assoc @sso* :client-secret "")))
                              (fn [_]
                                (reset! message* "Échec de validation. Vérifiez l’issuer OIDC et les identifiants du client."))))))))
        save-permissions
        (mf/use-fn
         (fn [event]
           (.preventDefault event)
           (when @selected-id*
             (->> (rp/cmd! :set-organization-permissions-settings
                           {:organization-id @selected-id* :permissions @permissions*})
                  (rx/subs! (fn [_] (reset! message* "Permissions enregistrées."))
                            (fn [_] (reset! message* "Impossible d’enregistrer les permissions.")))))))
        remove-member
        (mf/use-fn
         (fn [member-id]
           (when (js/confirm "Retirer ce membre de l’organisation ? Ses équipes et leurs fichiers seront conservés et transférés au propriétaire de l’organisation.")
             (->> (rp/cmd! :remove-organization-member
                           {:organization-id @selected-id* :member-id member-id})
                  (rx/subs! (fn [_]
                              (reset! message* "Membre retiré de l’organisation.")
                              (->> (rp/cmd! :get-organization-admin-summary {:organization-id @selected-id*})
                                   (rx/subs! #(reset! administration* %))))
                            (fn [_]
                              (reset! message* "Impossible de retirer ce membre. Vérifiez les droits sur ses équipes.")))))))
        delete-organization
        (mf/use-fn
         (fn []
           (when (js/confirm "Supprimer cette organisation ? Toutes ses données d’organisation et son journal d’audit seront supprimés. Les équipes doivent d’abord être déplacées ou supprimées.")
             (->> (rp/cmd! :delete-organization {:organization-id @selected-id*})
                  (rx/subs! (fn [_]
                              (reset! selected-id* nil)
                              (reset! message* "Organisation supprimée.")
                              (load-organizations))
                            (fn [_]
                              (reset! message* "Supprimez ou déplacez les équipes avant de supprimer l’organisation.")))))))]
    (mf/use-effect (fn [] (load-organizations) nil) [])
    (mf/use-effect
     (fn [] (when selected (reset! rename-name* (:name selected))) nil)
     [selected])
    (mf/use-effect
     (fn []
       (when @selected-id* (select-organization @selected-id*))
       nil)
     [@selected-id*])
    [:main {:style page-style}
     [:header {:style {:margin-bottom "28px"}}
      [:p {:style {:font-size "13px" :letter-spacing "0.08em" :text-transform "uppercase" :color "#686b78"}} "Administration Penpot"]
      [:h1 {:style {:font-size "32px" :margin "8px 0"}} "Console d’administration"]
      [:p {:style {:color "#5e606b"}} "Gérez vos organisations et leur connexion SSO."]]
     (when @message*
       [:div {:role "status" :style (merge card-style {:background "#f4f5f8"})} @message*])
     [:section {:style card-style}
      [:h2 {:style {:margin-top 0}} "Créer une organisation"]
      [:form {:on-submit create-organization}
       [:label {:style {:display "block" :font-size "14px" :font-weight 600}}
        "Nom de l’organisation"
        [:input {:style field-style :type "text" :required true :max-length 120
                 :value @create-name*
                 :on-change #(reset! create-name* (.. % -target -value))}]]
       [:button {:type "submit" :style {:padding "10px 16px" :border 0 :border-radius "6px" :background "#5546ff" :color "white"}}
        "Créer l’organisation"]]]
     [:section {:style {:display "grid" :grid-template-columns "minmax(240px, 0.8fr) minmax(360px, 1.5fr)" :gap "20px" :align-items "start"}}
      [:div {:style card-style}
       [:h2 {:style {:margin-top 0}} "Organisations"]
       (if (seq @organizations*)
         (for [{:keys [id name slug member-count team-count]} @organizations*]
           [:button {:key (str id) :type "button" :on-click #(select-organization id)
                     :aria-pressed (= id @selected-id*)
                     :style {:display "block" :width "100%" :text-align "left" :padding "14px" :margin-bottom "8px"
                             :border "1px solid #dedfe5" :border-radius "8px" :background (if (= id @selected-id*) "#f0efff" "white")}}
            [:strong name]
            [:span {:style {:display "block" :font-size "12px" :color "#686b78"}}
             (str member-count " membres · " team-count " équipes")]])
         [:p {:style {:color "#686b78"}} (if @loading* "Chargement…" "Aucune organisation pour le moment.")])]
      [:div {:style card-style}
       (if selected
         [:div
          [:h2 {:style {:margin-top 0}} (str "Paramètres · " (:name selected))]
          [:p {:style {:color "#686b78"}} (str "Identifiant : " (:id selected) " · slug : " (:slug selected))]
          [:form {:on-submit (fn [event]
                               (.preventDefault event)
                               (->> (rp/cmd! :rename-organization
                                             {:organization-id @selected-id* :name @rename-name*})
                                    (rx/subs! (fn [result]
                                                (reset! message* "Organisation renommée.")
                                                (swap! organizations*
                                                       (fn [items]
                                                         (mapv #(if (= (:id %) @selected-id*)
                                                                  (assoc % :name @rename-name* :slug (:slug result))
                                                                  %) items))))
                                              (fn [_] (reset! message* "Impossible de renommer l’organisation.")))))}
           [:label {:style {:display "block" :font-size "14px" :font-weight 600}}
            "Nom de l’organisation"
            [:input {:style field-style :type "text" :required true :max-length 120
                     :value @rename-name*
                     :on-change #(reset! rename-name* (.. % -target -value))}]]
           [:button {:type "submit" :style {:padding "10px 16px" :border 0 :border-radius "6px" :background "#5546ff" :color "white"}}
            "Renommer"]]
          [:h3 "Authentification unique (OIDC)"]
          [:p {:style {:font-size "13px" :color "#686b78"}}
           "Utilisez l’issuer de votre fournisseur OIDC. L’URL de callback Penpot à autoriser chez le fournisseur est : "
           [:code (str cf/public-uri "api/auth/oidc/callback")]]
          [:p {:style {:font-size "13px" :color "#686b78"}}
           "Le compte Penpot doit déjà exister. Le fournisseur doit renvoyer la même adresse e-mail que le compte Penpot, avec l’adresse déclarée vérifiée."]
          [:form {:on-submit save-sso}
           [:label {:style {:display "block" :margin "16px 0"}}
            [:input {:type "checkbox" :checked (boolean (:active @sso*))
                     :on-change #(swap! sso* assoc :active (.. % -target -checked))}]
            " Activer le SSO pour cette organisation"]
           (input-field "Issuer OIDC (pas l’URL .well-known)" (:issuer @sso*) "url"
                        #(swap! sso* assoc :issuer (.. % -target -value)))
           (input-field "Client ID" (:client-id @sso*) "text"
                        #(swap! sso* assoc :client-id (.. % -target -value)))
           (input-field "Client secret (laisser vide pour conserver l’actuel)" (:client-secret @sso*) "password"
                        #(swap! sso* assoc :client-secret (.. % -target -value)))
           [:button {:type "submit" :style {:padding "10px 16px" :border 0 :border-radius "6px" :background "#5546ff" :color "white"}}
            "Valider et enregistrer"]]
          [:hr {:style {:margin "28px 0" :border "0" :border-top "1px solid #dedfe5"}}]
          [:h3 "Permissions de l’organisation"]
          [:p {:style {:font-size "13px" :color "#686b78"}} "Ces règles s’appliquent aux équipes rattachées à l’organisation."]
          [:form {:on-submit save-permissions}
           (permission-field "Création d’équipes" :create-teams [["any" "Tous les membres"] ["onlyMe" "Propriétaire de l’organisation uniquement"]] permissions*)
           (permission-field "Suppression d’équipes" :delete-teams [["onlyOwners" "Propriétaires d’équipe et d’organisation"] ["never" "Propriétaire de l’organisation uniquement"]] permissions*)
           (permission-field "Déplacement d’équipes" :move-teams [["always" "Autorisé"] ["myOrganizations" "Entre mes organisations"] ["never" "Désactivé"]] permissions*)
           (permission-field "Envoi d’invitations" :send-invitations [["ownersAndAdmins" "Propriétaires et admins"] ["owners" "Propriétaires uniquement"]] permissions*)
           (permission-field "Nouveaux membres dans les équipes" :new-team-members [["anyone" "Tout le monde"] ["members" "Membres de l’organisation uniquement"]] permissions*)
           [:button {:type "submit" :style {:padding "10px 16px" :border 0 :border-radius "6px" :background "#5546ff" :color "white"}}
            "Enregistrer les permissions"]]
          [:hr {:style {:margin "28px 0" :border "0" :border-top "1px solid #dedfe5"}}]
          [:h3 "Équipes"]
          [:form {:on-submit (fn [event]
                               (.preventDefault event)
                               (when-not (str/blank? (str/trim @create-team-name*))
                                 (->> (rp/cmd! :create-organization-team
                                               {:organization-id @selected-id* :name @create-team-name*})
                                      (rx/subs! (fn [_]
                                                  (reset! create-team-name* "")
                                                  (reset! message* "Équipe créée.")
                                                  (->> (rp/cmd! :get-organization-admin-summary {:organization-id @selected-id*})
                                                       (rx/subs! #(reset! administration* %)))
                                                  (load-organizations))
                                                (fn [_] (reset! message* "Impossible de créer l’équipe."))))))}
           [:label {:style {:display "block" :font-size "14px" :font-weight 600}}
            "Nouvelle équipe"
            [:input {:style field-style :type "text" :required true :max-length 120
                     :value @create-team-name*
                     :on-change #(reset! create-team-name* (.. % -target -value))}]]
           [:button {:type "submit" :style {:padding "10px 16px" :border 0 :border-radius "6px" :background "#5546ff" :color "white"}}
            "Créer une équipe"]]
          (if (seq (:teams @administration*))
            [:ul {:style {:padding-left "22px"}}
             (for [{:keys [id name member-count]} (:teams @administration*)]
               [:li {:key (str id) :style {:padding "6px 0"}}
                [:button {:type "button" :on-click #(st/emit! (rt/nav :dashboard-recent {:team-id id}))
                          :style {:border 0 :background "transparent" :color "#5546ff" :cursor "pointer"}}
                 name]
                (str " · " member-count " membres")])]
            [:p {:style {:color "#686b78"}} "Aucune équipe rattachée."])
          [:h3 "Membres de l’organisation"]
          (if (seq (:members @administration*))
            [:ul {:style {:padding-left "22px"}}
             (for [{:keys [id name email]} (:members @administration*)]
               [:li {:key (str id) :style {:padding "6px 0"}}
                [:strong (or name email)]
                (when name [:span {:style {:color "#686b78"}} (str " · " email)])
                (when (not= id (:owner-id selected))
                  [:button {:type "button" :on-click #(remove-member id)
                            :style {:margin-left "10px" :border 0 :background "transparent" :color "#b42318" :cursor "pointer"}}
                   "Retirer"])])]
            [:p {:style {:color "#686b78"}} "Aucun membre."])
          [:hr {:style {:margin "28px 0" :border "0" :border-top "1px solid #dedfe5"}}]
          [:h3 "Journal d’audit"]
          (if (seq @audit*)
            [:ol {:style {:padding-left "22px"}}
             (for [{:keys [id event-name profile-id occurred-at event]} @audit*]
               [:li {:key (str id) :style {:padding "10px 0" :border-bottom "1px solid #eee"}}
                [:strong (str/replace event-name #"-" " ")]
                [:div {:style {:font-size "12px" :color "#686b78"}}
                 (str occurred-at " · profil " profile-id)]
                [:details
                 [:summary {:style {:cursor "pointer" :font-size "12px"}} "Détails de l’événement"]
                 [:pre {:style {:overflow "auto" :font-size "11px" :white-space "pre-wrap"}}
                  (pr-str (select-keys event [:props :context :source]))]]])]
            [:p {:style {:color "#686b78"}} "Aucun événement récent."])
          [:hr {:style {:margin "28px 0" :border "0" :border-top "1px solid #dedfe5"}}]
          [:button {:type "button" :on-click delete-organization
                    :style {:padding "10px 16px" :border "1px solid #b42318" :border-radius "6px" :background "white" :color "#b42318"}}
           "Supprimer l’organisation"]]
         [:p {:style {:color "#686b78"}} "Sélectionnez une organisation pour gérer son SSO."])]]]))

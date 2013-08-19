(ns pallet.crate.ssh-key
  "Crate functions for manipulating SSH-keys"
  (:require
   [clojure.string :as string]
   [pallet.actions
    :refer [directory
            exec-checked-script
            file
            remote-file
            remote-file-content
            sed]]
   [pallet.crate :refer [admin-user defplan]]
   [pallet.script.lib :as lib]
   [pallet.script.lib :refer [user-home]]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [fragment with-source-line-comments]]))

(defn user-ssh-dir [user]
  (str
   (with-source-line-comments false
     (stevedore/script (~lib/user-home ~user)))
   "/.ssh/"))

(defplan authorize-key
  "Authorize a public key on the specified user."
  [user public-key-string & {:keys [authorize-for-user]}]
  (let [target-user (or authorize-for-user user)
        dir (user-ssh-dir target-user)
        auth-file (str dir "authorized_keys")]
    (directory dir :owner target-user :mode "755")
    (file auth-file :owner target-user :mode "644")
    (exec-checked-script
     (format "authorize-key on user %s" user)
     (var auth_file ~auth-file)
     (if-not ("fgrep" (quoted ~(string/trim public-key-string)) @auth_file)
       (println (quoted ~public-key-string) ">>" @auth_file)))
    (exec-checked-script
     "Set selinux permissions"
     (~lib/selinux-file-type ~dir "user_home_t"))))

(defplan authorize-key-for-localhost
  "Authorize a user's public key on the specified user, for ssh access to
  localhost.  The :authorize-for-user option can be used to specify the
  user to who's authorized_keys file is modified."
  [user public-key-filename & {:keys [authorize-for-user] :as options}]
  (let [target-user (or authorize-for-user user)
        key-file (str (user-ssh-dir user) public-key-filename)
        auth-file (str (user-ssh-dir target-user) "authorized_keys")]
    (directory
     (user-ssh-dir target-user) :owner target-user :mode "755")
    (file auth-file :owner target-user :mode "644")
    (exec-checked-script
     "authorize-key"
     (var key_file ~key-file)
     (var auth_file ~auth-file)
     (if-not ("grep" (quoted @("cat" @key_file)) @auth_file)
       (do
         (print (quoted "from=\\\"localhost\\\" ") ">>" @auth_file)
         ("cat" @key_file ">>" @auth_file))))))

(defplan install-key
  "Install a ssh private key."
  [user key-name private-key-string public-key-string]
  (let [ssh-dir (user-ssh-dir user)]
    (directory ssh-dir :owner user :mode "755")
    (remote-file
     (str ssh-dir key-name)
     :owner user :mode "600"
     :content private-key-string)
    (remote-file
     (str ssh-dir key-name ".pub")
     :owner user :mode "644"
     :content public-key-string)))

(def ssh-default-filenames
     {"rsa1" "identity"
      "rsa" "id_rsa"
      "dsa" "id_dsa"})

(defplan generate-key
  "Generate an ssh key pair for the given user, unless one already
   exists. Options are:
     :filename path -- output file name (within ~user/.ssh directory)
     :type key-type -- key type selection
     :no-dir true   -- do note ensure directory exists
     :passphrase    -- new passphrase for encrypting the private key
     :comment       -- comment for new key"
  [user & {:keys [type filename passphrase no-dir comment]
           :or {type "rsa" passphrase ""}
           :as  options}]

  (let [key-type type
        ^String path (stevedore/script
                      ~(str (user-ssh-dir user)
                            (or filename (ssh-default-filenames key-type))))
        ssh-dir (.getParent (java.io.File. path))]
    (when-not (or (:no-dir options))
      (directory ssh-dir :owner user :mode "755"))
    (exec-checked-script
     "ssh-keygen"
     (var key_path ~path)
     (if-not (file-exists? @key_path)
       ("ssh-keygen" ~(stevedore/map-to-arg-string
                       {:f (stevedore/script @key_path)
                        :t key-type
                        :N passphrase
                        :C (or (:comment options "generated by pallet"))}))))
    (file path :owner user :mode "0600")
    (file (str path ".pub") :owner user :mode "0644")))

(defplan public-key
  "Returns the public key for the specified remote `user`. By default it returns
the user's id_rsa key from `~user/.ssh/id_rsa.pub`.

You can specify a different key type by passing :type. This assumes the public
key has a `.pub` extension.

Passing a :filename value allows direct specification of the filename.

`:dir` allows specification of a different location."
  [user & {:keys [filename dir type] :or {type "rsa"} :as options}]
  (let [filename (or filename (str (ssh-default-filenames type) ".pub"))
        path (str (or dir (user-ssh-dir user)) filename)]
    (remote-file-content path)))

(defplan config
  "Update an ssh config file. Sets the configuration for `host` to be that given
by the key-value-map.  Optionally allows specification of the `user` whose ssh
config file is to be modified, and the full `config-file` path."
  [host key-value-map & {:keys [user config-file]
                         :or {user (:username (admin-user))}}]
  (let [content (str "Host " host \newline
                     (string/join \newline
                                  (map
                                   #(str "  " (first %) " = " (second %))
                                   key-value-map)))
        config-file (or config-file
                        (fragment (lib/file (user-home ~user) ".ssh" config)))]
    (file config-file :owner user :mode "600") ; ensure it exists
    (sed config-file (str "{ /^Host " host "/d; /^Host / !d ;}") ;remove old
         :quote-with "'" :restriction (str "/^Host " host "/,/^Host/"))
    (exec-checked-script
     "Append ssh config"
     (println (str "'" ~content "'") ">>" ~config-file))))

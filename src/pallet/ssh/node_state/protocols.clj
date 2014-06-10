(ns pallet.ssh.node-state.protocols
  "Protocols for node state updates")

(defprotocol FileBackup
  (new-file-content [_ session path options]
    "Notify that path has been modified.
    Options include, :versioning, :no-versioning, :max-versions"))

(defprotocol FileChecksum
  (verify-checksum [_ session path]
    "Verify the expected MD5 of the file at path.")
  (record-checksum [_ session path]
    "Save the MD5 for the file at path."))

(defprotocol NodeSetup
  (setup-node-script [_ session usernames]
    "Setup paths on the node for usernames"))

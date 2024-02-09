(ns xcl.definitions)

(defrecord LinkStructure
    [link
     protocol
     resource-resolver-method
     resource-resolver-path
     content-resolvers
     post-processors])

(defrecord GitResourceAddress
    [link repo-name oid path content-resolvers])

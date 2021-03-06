
require('core/string');
importPackage(com.google.appengine.api.datastore);

export('Storable');

var Storable = require('./storable').Storable;
Storable.setStoreImplementation(this);

var __shared__ = true;
var datastore = DatastoreServiceFactory.getDatastoreService();

// HACK: google datastore only allows entities in the same entity group
// to be modified in the same transaction, so we put all our app's data
// into one big entity group
var rootKey = KeyFactory.createKey("Root", "root");

function all(type) {
    var result = [];
    var i = datastore.prepare(new Query(type)).asIterator();
    while (i.hasNext()) {
        result.push(new Storable(type, i.next()));
    }
    return result;
}

function get(type, id) {
	var key = KeyFactory.createKey(rootKey, type, id);
	if (!isKey(key)) {
		throw Error("Storable.get() called with non-key argument");
	}
	return new Storable(key.getKind(), datastore.get(key));
}

function save(props, entity, entities) {
    if (entities && entities.contains(entity)) {
        return;
    }
    var tx;
    var isRoot = false;
    if (!entities) {
        isRoot = true;
        entities = new java.util.HashSet();
        tx = datastore.beginTransaction();
    }
    entities.add(entity);
    for (var id in props) {
        var value = props[id];
        if (isStorable(value)) {
            value.save(entities);
            value = value._key;
        } else if (value instanceof Array) {
            var list = new java.util.ArrayList();
            value.forEach(function(obj) {
                if (obj instanceof Storable) {
                    obj.save(entities);
                    list.add(obj._key);
                } else {
                    list.add(obj);
                }
            });
            value = list;
        } else if (typeof value === 'string' && value.length > 500) {
            // maximal length for ordinary strings is 500 chars in datastore
            value = new Text(value); 
        }
        entity.setProperty(id, value);
    }
    if (isRoot) {
        try {
            datastore.put(entities);
            tx.commit();
        } catch (e) {
            tx.rollback();
            throw e;
        }
    }
}

function remove(key) {
    datastore['delete'](key);
}

function equalKeys(key1, key2) {
    return key1 && key1.equals(key2);
}

function getEntity(type, arg) {
    if (isKey(arg)) {
        return datastore.get(arg);
    } else if (isEntity(arg)) {
        return arg;
    } else if (arg instanceof Object) {
        // HACK: we generate our own ids as google autogenerated ids
        // are only created when entities are actually stored, but we
        // need them before to resolve references in pre-store
        var id, idlength = 4;
        do {
            id = "k" + String.random(idlength++);
            var entity = new Entity(type, id, rootKey);
            try {
                datastore.get(entity.getKey()) != null
            } catch (notfound) {
                break;
            }
        } while(true);
        return entity;
    }
    return null;
}

function getKey(type, arg) {
    if (isKey(arg)) {
        return arg;
    } else if (arg instanceof Entity) {
        return arg.getKey();
    }
    return null;
}

function getProps(type, arg) {
    if (arg instanceof Object) {
        return arg;
    } else if (arg instanceof Entity) {
        var props = {};
        var map = new ScriptableMap(arg.getProperties());
        for (var i in map) {
            var value = map[i];
            if (isKey(value)) {
                value = new Storable(value.getKind(), value);
            } else if (value instanceof java.util.List) {
                var array = [];
                for (var it = value.iterator(); it.hasNext(); ) {
                    var obj = it.next();
                    array.push(isKey(obj) ?
                               new Storable(obj.getKind(), obj) : obj);
                }
                value = array;
            } else if (value instanceof Text) {
                value = value.getValue();
            } else {
                value = Context.javaToJS(value, global);
            }
            props[i] = value;
        }
        return props;
    }
    return null;
}

function getId(key) {
    return key ? key.getName() : null;
}

function isEntity(value) {
    return value instanceof Entity;
}

function isKey(value) {
    return value instanceof Key;
}

function isStorable(value) {
    return value instanceof Storable;
}


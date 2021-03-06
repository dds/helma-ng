include('helma/webapp/response');
include('helma/system');
include('helma/jsdoc');
require('core/array');

exports.index = function index(req, module) {
    var repo = new ScriptRepository(getRepositories()[1]);
    if (module) {
        var jsdoc = [];
        res = repo.getScriptResource(module);
        var currentDoc;
        parseScriptResource(res, function(node) {
            if (node.jsDoc) {
                currentDoc = extractTags(node.jsDoc)
                print(currentDoc[0][1]);
                jsdoc.push(currentDoc);
            } else {
                print(getTypeName(node) + " // " + getName(node));
                if (isName(node) && getName(node) != "exports" && currentDoc && !currentDoc.name) {
                    Object.defineProperty(currentDoc, 'name', {value: getName(node)});
                }
            }
            return true;
        });
        return new SkinnedResponse('skins/module.html', {
            jsdoc: jsdoc
        });
    } else {
        var modules = repo.getScriptResources(true).sort(function(a, b) {return a.relativePath > b.relativePath});
        return new SkinnedResponse('skins/index.html', {
            modules: modules
        });
    }
}



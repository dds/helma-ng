// a simple web app/module
include('helma/webapp/response');

export('index');

function index(req, res) {
    var context = {
        title: 'Modules',
        href: req.pathDecoded
    };
    return new SkinnedResponse('skins/modules.txt', context);
}

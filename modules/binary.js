
var asJavaString = require('helma/system').asJavaString;

var Binary = exports.Binary = function(bytes) {
    this.bytes = bytes;
};

Binary.prototype.getLength = function() {
    return this.bytes.length;
};

Binary.prototype.toString = function(encoding) {
    var jstr = encoding ?
               new java.lang.String(this.bytes, encoding) :
               new java.lang.String(this.bytes);
    return String(jstr);
};

String.prototype.toBinary = function(encoding) {
    var bytes = encoding ?
                asJavaString(this).getBytes(encoding) :
                asJavaString(this).getBytes();
    return new Binary(bytes);
};

Binary.prototype.forEach = function(block) {
    block(this);
};

Binary.prototype.toBinary = function() {
    return this;
};


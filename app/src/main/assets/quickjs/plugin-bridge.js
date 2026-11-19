(function (host) {
    "use strict";
    const global = globalThis;
    delete global.__materialBoardHost;

    const parse = JSON.parse;
    const stringify = JSON.stringify;
    const keys = Object.keys;
    const define = Object.defineProperty;
    const withoutPrototype = Object.setPrototypeOf;
    const isArray = Array.isArray;
    const finite = Number.isFinite;
    const number = Number;
    const string = String;
    const ErrorType = Error;
    const apply = Reflect.apply;
    const callbacks = new Map();
    const getCallback = callbacks.get.bind(callbacks);
    const setCallback = callbacks.set.bind(callbacks);
    const deleteCallback = callbacks.delete.bind(callbacks);
    let nextCallback = 1;

    // Prevent a plugin's Array.prototype.toJSON from changing the wire format.
    function tuple(...items) {
        return withoutPrototype(items, null);
    }

    function encode(value, depth) {
        if (depth > 32 || value === null) return null;
        switch (typeof value) {
            case "undefined": return tuple("u");
            case "number": return finite(value) ? value : tuple("n", string(value));
            case "string":
            case "boolean": return value;
            case "function": {
                const id = nextCallback++;
                if (id > 2147483647) throw new ErrorType("Too many QuickJS callbacks");
                setCallback(id, value);
                return tuple("f", id);
            }
            case "object": {
                const result = tuple();
                if (isArray(value)) {
                    for (let i = 0; i < value.length && i < 100000; i++) {
                        result[i] = encode(value[i], depth + 1);
                    }
                    return tuple("a", result);
                }
                const names = keys(value);
                for (let i = 0; i < names.length && i < 100000; i++) {
                    const name = names[i];
                    result[i] = tuple(name, encode(value[name], depth + 1));
                }
                return tuple("o", result);
            }
            default: return string(value);
        }
    }

    function installProperty(object, name, value) {
        // Define an own data property even for a key such as "__proto__".
        define(object, name, { __proto__: null, value, writable: true, configurable: true, enumerable: true });
    }

    function decode(value) {
        if (!isArray(value)) return value;
        switch (value[0]) {
            case "u": return undefined;
            case "n": return number(value[1]);
            case "f": return getCallback(value[1]);
            case "j":
                try { return parse(value[1]); } catch (_) { return undefined; }
            case "a": {
                const array = [];
                const items = value[1];
                for (let i = 0; i < items.length; i++) installProperty(array, i, decode(items[i]));
                return array;
            }
            case "o": {
                const object = {};
                const entries = value[1];
                for (let i = 0; i < entries.length; i++) {
                    const key = entries[i][0];
                    const item = entries[i][1];
                    const decoded = decode(item);
                    if (decoded !== undefined || !isArray(item) || item[0] !== "j") {
                        installProperty(object, key, decoded);
                    }
                }
                return object;
            }
            case "b": {
                const id = value[1];
                const binding = function (...args) {
                    const wire = tuple();
                    for (let i = 0; i < args.length; i++) wire[i] = encode(args[i], 0);
                    const reply = parse(host(id, stringify(wire)));
                    if (!reply[0]) throw new ErrorType(reply[1]);
                    return decode(reply[1]);
                };
                define(binding, "name", { __proto__: null, value: value[2] });
                return binding;
            }
            default: throw new ErrorType("Unknown QuickJS host value");
        }
    }

    return function (request) {
        const command = parse(request);
        if (command[0] === "install") {
            const bindings = decode(command[1]);
            const names = keys(bindings);
            for (let i = 0; i < names.length; i++) installProperty(global, names[i], bindings[names[i]]);
            return "[null]";
        }
        const expired = command[3];
        for (let i = 0; i < expired.length; i++) deleteCallback(expired[i]);
        const result = apply(getCallback(command[1]), global, decode(command[2]));
        return command[4] ? stringify(tuple(encode(result, 0))) : "[null]";
    };
})(globalThis.__materialBoardHost);

window.sirius.Continuity = (function () {
    /**@
     * Initializes the Continuity history and deep-linking manager.
     *
     * @param {Object} [options] map specifying the behaviour of the library:
     * @param {string} options.stateParameterName Defines the parameter name the history state is written to in the deeplink URL.
     * @param {boolean} [options.debug] Allows to enable the printing of debugging messages to the console right from the start.
     * @param {function} [options.initialStateHook] Called during the parsing of the initial deep-linked state. Can be used to create intermediate states the user can navigate back to.
     * @param {Object} options.entryTypes Defines the list of known entry types that should be handled (state handlers can be registered later).
     * @param {boolean} [options.entryTypes.excludeFromParameter] Allows to exclude the information of a state entry type from the generated deep-link URL (to just keep it in the JS history).
     */
    function Continuity(options) {
        this.options = options;
        this.stateEntryHandlers = {};
        this.lastState = null;
        this.currentState = null;

        this.searchParams = new URLSearchParams(window.location.search);

        this.debug(this.options.debug);

        this.dispatchEvent('continuity-configured');

        this.loadInitialState();
        this.installStateListener();

        this.dispatchEvent('continuity-initialized');
    }

    /**
     * Installs the `popstate` event listener during the initial setup which handles state changes during history navigation.
     */
    Continuity.prototype.installStateListener = function () {
        const me = this;
        // This is fired when history.back or history.forward is called
        window.addEventListener('popstate', function (event) {
            let state = history.state;

            if ((state === null) || (state === undefined)) state = event.state;
            if ((state === null) || (state === undefined)) state = window.event.state;

            if (state !== null) {
                me.log('[CONTINUITY] [ACTION] Navigation occurred.');
                me.handleStateChange(state);
            }
        });
    };

    Continuity.prototype.loadInitialState = function () {
        // Handles first visit navigation
        const urlState = this.searchParams.get(this.options.stateParameterName);
        if (urlState) {
            this.currentState = this.parseStateUrlParam(urlState);
            this.handleStateChange(this.currentState, true);
        } else {
            this.currentState = {};
        }

        // Before actually setting our initial state we give the caller the possibility to create some intermediate states.
        // This is especially useful to allow the user to press back even when coming from a deeplink URL (without a previous history stack).
        // We only do this when the current history entry has no attached state, so we prevent unwanted history states when reloading the site.
        let shouldReplaceInitialState = !this.options.initialStateHook || window.history.state;
        if (this.options.initialStateHook && !window.history.state) {
            const lastState = this.options.initialStateHook.call(this, this.currentState);
            if (lastState) {
                // Remember the last state, so we know we can go back.
                this.lastState = lastState;
            } else {
                // We called the hook but did not create any intermediate states -> we should replace the empty history entry.
                shouldReplaceInitialState = true;
            }
        }

        if (shouldReplaceInitialState) {
            // No intermediate states where created, so we should replace the history entry without any state with our initial one.
            history.replaceState(this.currentState, null, this.buildUrl());
        } else {
            // The initial state hook created some intermediate states, so we should push the URL state to not replace them.
            history.pushState(this.currentState, null, this.buildUrl());
        }
    };

    /**
     * Allows to register a new state entry handler (or replace the currently registered one) for the given type.
     *
     * @param {string} entryType the type/key of the state entry the handler is responsible for (as defined in `options.entryTypes` during initialization).
     * @param {Object} handler the state entry handler to register
     * @param {function} [handler.restore] Callback function called when a new or updated history state entry is present for the provided entry type. The entry is passed to the callback as parameter.
     * @param {function} [handler.destroy] Callback function called when the provided entry type is no longer present in the new history state.
     */
    Continuity.prototype.registerStateEntryHandler = function (entryType, handler) {
        this.log('[CONTINUITY] [INTERNAL] Registered state handler for type "%s".', entryType);
        this.stateEntryHandlers[entryType] = handler;

        let entry = this.currentState ? this.currentState[entryType] : null;
        if (entry) {
            this.log('[CONTINUITY] [INTERNAL] State handler of type "%s" called for entry: %o', entryType, entry);
            handler.restore.call(this, entry);
            // Update the URL to include the entry of the new handler.
            this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));
            history.replaceState(this.currentState, null, this.buildUrl());
        }
    };

    /**
     * Handles the change to the given state by calling the responsible state entry handles and updating some internal fields.
     * For state entries contained in the new history state the `restore` callback is triggered, for omitted state entries the `destroy` callback is triggered.
     *
     * @param {Object} state the new state that should be handled
     * @param {boolean} dontSetLastState whether the internal last state should be replaced by the current history state (before the new one became active)
     */
    Continuity.prototype.handleStateChange = function (state, dontSetLastState) {
        if (!dontSetLastState) {
            this.lastState = this.currentState;
        }
        this.currentState = state;

        const me = this;
        Object.keys(state).forEach(function (entryKey) {
            const handler = me.stateEntryHandlers[entryKey];
            if (handler) {
                const entry = state[entryKey];
                me.log('[CONTINUITY] [INTERNAL] State handler of type "%s" called for entry: %o', entryKey, entry);
                handler.restore.call(me, entry);
            } else {
                me.log('[CONTINUITY] [INTERNAL] State handler of type "%s" not found!', entryKey);
            }
        });
        Object.keys(this.stateEntryHandlers).forEach(function (entryKey) {
            if (!state[entryKey]) {
                me.log('[CONTINUITY] [INTERNAL] State handler of type "%s" called to destroy!', entryKey);
                me.stateEntryHandlers[entryKey].destroy.call(me);
            }
        });
    };

    /**
     * Pushes a new history state. This is a brute-force option when all entries should be affected by an operation.
     * This is essentially the same as `replaceEntireState` but calls `history.pushState` at the end.
     *
     * @param {Object} newState the state entries that should be pushed to the history stack
     */
    Continuity.prototype.pushEntireState = function (newState) {
        this.log('[CONTINUITY] [ACTION] Pushing entire new state: %o', newState);

        this.lastState = this.currentState;
        this.currentState = newState;
        this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));

        // Creates a new history instance, and saves state on it
        history.pushState(this.currentState, null, this.buildUrl());
    }

    /**
     * Replaces current state with the given one. This is a brute-force option when all entries should be affected by an operation.
     * This is essentially the same as `pushEntireState` but calls `history.replaceState` at the end.
     *
     * @param {Object} newState the state entries the current entry in the history stack should be replaced by
     */
    Continuity.prototype.replaceEntireState = function (newState) {
        this.log('[CONTINUITY] [ACTION] Replacing entire new state: %o', newState);

        this.currentState = newState;
        this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));

        // Creates a new history instance, and saves state on it
        history.replaceState(this.currentState, null, this.buildUrl());
    }

    /**
     * Pushes a new history state including given entry. This also works when no entry of the given type is present in the current history state.
     * This is essentially the same as `replaceStateEntry` but calls `history.pushState` after constructing the new state.
     *
     * @param {string} type the type of the state entry to push
     * @param {Object} entry the information that should be pushed
     */
    Continuity.prototype.pushStateEntry = function (type, entry) {
        this.log('[CONTINUITY] [ACTION] Pushing new state for type "%s": %o', type, entry);

        this.lastState = this.currentState;
        this.currentState[type] = entry;
        this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));

        // Creates a new history instance, and saves state on it
        history.pushState(this.currentState, null, this.buildUrl());
    };

    /**
     * Replaces the entry for the given type in the current history state. This also works when no entry of the given type is present in the current history state.
     * This is essentially the same as `pushStateEntry` but calls `history.replaceState` after constructing the new state.
     *
     * @param {string} type the type of the state entry to replace
     * @param {Object} entry the information the old entry should be replaced by
     */
    Continuity.prototype.replaceStateEntry = function (type, entry) {
        this.log('[CONTINUITY] [ACTION] Replacing state for type "%s": %o', type, entry);

        this.currentState[type] = entry;
        this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));

        // Creates a new history instance, and saves state on it
        history.replaceState(this.currentState, null, this.buildUrl());
    };

    /**
     * Removes the entry for the given type from the current history state when present.
     *
     * This is done in two ways:
     * - if a last state is present we jump back to it
     * - otherwise we create a new state (as copy from the current state) and remove the entry there. The new state is then pushed to the history stack.
     *
     * @param {string} type the type of the state entry to remove
     */
    Continuity.prototype.popStateEntry = function (type) {
        this.log('[CONTINUITY] [ACTION] Popping state for type "%s"', type);
        if (this.lastState) {
            history.back();
        } else {
            this.lastState = this.currentState;
            delete this.currentState[type];
            this.searchParams.set(this.options.stateParameterName, this.buildStateUrlParam(this.currentState));

            history.pushState(this.currentState, null, this.buildUrl());
            this.handleStateChange(this.currentState);
        }
    };

    /**
     * Allows to check whether the current history state has an entry for the given type.
     *
     * @param {string} entryType the history state entry type to check for.
     * @returns {boolean} `true` when the current history state contains an entry for the type.
     */
    Continuity.prototype.hasStateEntry = function (entryType) {
        return !!this.getStateEntry(entryType);
    };

    /**
     * Allows to retrieve the entry of the given type from the current history state.
     *
     * @param {string} entryType the history state entry type to retrieve.
     * @returns {null|Object} the state entry for the given type.
     */
    Continuity.prototype.getStateEntry = function (entryType) {
        if (this.currentState) {
            return this.currentState[entryType];
        }
        if (window.history.state) {
            return window.history.state[entryType];
        }
        return null;
    };

    /**
     * Serializes the given state to be used as the value of our history state deep-link parameter in the URL.
     * This filters out all entry types that where defined as `excludeFromParameter` during initialization.
     *
     * @param {Object} state the history state to serialize
     * @returns {string} the state as a single string used in the deep-link URL
     */
    Continuity.prototype.buildStateUrlParam = function (state) {
        const paramState = {};
        for (var entryKey in state) {
            if (this.options.entryTypes[entryKey] && !this.options.entryTypes[entryKey].excludeFromParameter) {
                paramState[entryKey] = state[entryKey];
            }
        }

        return JSON.stringify(paramState);
    };

    /**
     * Parses the value of our history state deep-link parameter from the URL and transforms it into a JS Object.
     *
     * @param {string} param the value of the parameter to parse
     * @returns {Object} the history state as an object
     */
    Continuity.prototype.parseStateUrlParam = function (param) {
        return JSON.parse(param);
    };

    /**
     * Generates the new URL by combining the given search parameters and hash (if present).
     *
     * @param {URLSearchParams} [searchParams] the URL parameters to include (or the ones stored in the lib scope when omitted)
     */
    Continuity.prototype.buildUrl = function (searchParams) {
        const effectiveSearchParams = searchParams || this.searchParams;
        let url = '?' + effectiveSearchParams;
        if (window.location.hash) {
            // We want to keep the fragment component of the URL intact.
            url += window.location.hash;
        }
        return url;
    };

    /**
     * Toggles the printing of debug messages by the library.
     *
     * @param {boolean} loggingEnabled whether logging should be enabled
     */
    Continuity.prototype.debug = function (loggingEnabled) {
        if (loggingEnabled) {
            this.log = console.log.bind(window.console);
        } else {
            this.log = function () {
                // Intentionally left empty to only log when debugging is enabled.
            }
        }
    };

    /**
     * Allows to dispatch a custom event of the given type while handling different browser environments. For internal use only.
     *
     * @param {boolean} eventType the type of event to trigger
     */
    Continuity.prototype.dispatchEvent = function (eventType) {
        if (typeof (CustomEvent) === 'function') {
            document.dispatchEvent(new CustomEvent(eventType, {detail: this}));
        } else {
            const event = document.createEvent('Event');
            event.initEvent(eventType, true, true);
            event.detail = this;
            document.dispatchEvent(event);
        }
    }

    return Continuity;
})();

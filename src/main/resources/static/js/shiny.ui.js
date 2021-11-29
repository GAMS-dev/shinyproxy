/*
 * ShinyProxy
 *
 * Copyright (C) 2016-2021 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
 /**
 * Modifications copyright (C) GAMS Development Corp. <support@gams.com>
 */
Shiny = window.Shiny || {};
Shiny.ui = {
    /***
     * Setups the iframe of the application.
     */
    setupIframe: function () {
        var $iframe = $('<iframe id="shinyframe" width="100%" style="display:none;overflow:hidden;height:100vh;" frameBorder="0"></iframe>')
        // IMPORTANT: start the injector before setting the `src` property of the iframe
        // This is required to ensure that the polling catches all events and therefore the injector works properly.
        // Shiny.connections.startInjector();
        $iframe.attr("src", Shiny.app.staticState.containerPath);
        $('#iframeinsert').before($iframe); // insert the iframe into the HTML.
    },

    /**
     * Shows the loading page.
     */
    showLoading: function () {
        $('#appStopped').hide();
        $('#shinyframe').hide();
        $("#loading").show();
    },

    /**
     * Shows the reconnecting page.
     */
    showReconnecting: function() {
        $('#appStopped').hide();
        $('#shinyframe').hide();
        $("#loading").show();
    },

    /**
     *  Hides the loading pages and shows the iframe;
     */
    showFrame: function () {
        $('#shinyframe').show();
        $("#loading").fadeOut("slow", () => {
            $("#loadingAnimation").show();
            $("#loadAppError").hide();
        });
    },

    showFailedToReloadPage: function () {
        $('#shinyframe').hide();
        $("#loading").hide();
        $("#reloadFailed").show();
    },

    showStoppedPage: function() {
        $('#shinyframe').remove();
        $("#loading").hide();
        $('#appStopped').show();
    },

    showLoggedOutPage: function() {
        if (!Shiny.app.runtimeState.navigatingAway) {
            // only show it when not navigating away, e.g. when logging out in the current tab
            $('#shinyframe').remove();
            $("#loading").hide();
            $('#userLoggedOut').show();
        }
    },

    removeFrame() {
        $('#shinyframe').remove();
    }
}
/*
 * ShinyProxy
 *
 * Copyright (C) 2016-2023 Open Analytics
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
        $iframe.attr("src", Shiny.app.runtimeState.containerPath);
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
    showReconnecting: function () {
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

    showResumingPage: function () {
        $('#shinyframe').hide();
        $("#loading").show();
    },

    showStoppingPage: function () {
        $('#shinyframe').hide();
        $("#loading").show();
    },

    showPausingPage: function () {
        $('#shinyframe').hide();
        $("#loading").show();
    },

    showPausedAppPage: function () {
        $('#shinyframe').remove();
        $("#loading").hide();
        $('#appPaused').show();
        $("#navbarWrapper").show();
    },

    showFailedToReloadPage: function () {
        $('#shinyframe').remove();
        $("#loading").hide();
        $("#reloadFailed").show();
        $("#navbarWrapper").show();
    },

    showStartFailedPage: function () {
        $('#shinyframe').hide();
        $("#loading").hide();
        $("#startFailed").show();
        $("#navbarWrapper").show();
    },

    showStoppedPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        $('#shinyframe').remove();
        $("#loading").hide();
        $("#navbarWrapper").show();
        if (!$('#appCrashed').is(":visible")) {
            $('#appStopped').show();
        }
    },

    showCrashedPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        $('#shinyframe').remove();
        $("#loading").hide();
        $('#appCrashed').show();
        $("#navbarWrapper").show();
    },

    showLoggedOutPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        if (!Shiny.app.runtimeState.navigatingAway) {
            // only show it when not navigating away, e.g. when logging out in the current tab
            $('#shinyframe').remove();
            $("#loading").hide();
            $("#navbar").hide();
            $('#userLoggedOut').show();
            $("#navbarWrapper").show();
        }
    },

    removeFrame() {
        $('#shinyframe').remove();
    },
    
    getTimeZone() {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone;
        } catch {
            return null;
        }
    }
}

Handlebars.registerHelper('formatStatus', function (status) {
    return Shiny.ui.formatStatus(status);
});

/*
 * ShinyProxy
 *
 * Copyright (C) 2016-2024 Open Analytics
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
function waitForShinyFrameLoadingComplete(callback) {
    const shinyFrame = document.getElementById('shinyframe');
    if (!shinyFrame) {
        console.error("#shinyframe iframe not found.");
        return;
    }
    shinyFrame.onload = () => {
        const shinyFrameDoc = shinyFrame.contentDocument || shinyFrame.contentWindow.document;
        const intervalId = setInterval(() => {
            const loadingScreen = shinyFrameDoc.querySelector('#loading-screen');
            if (loadingScreen && getComputedStyle(loadingScreen).display === 'none') {
                clearInterval(intervalId);
                callback();
            }
        }, 500);
    };
}

Shiny = window.Shiny || {};
Shiny.ui = {
    /***
     * Setups the iframe of the application.
     */
    setupIframe: function () {
        var $iframe = $('<iframe id="shinyframe" width="100%" style="display:none;overflow:hidden;height:100vh;" frameBorder="0"></iframe>')
        $iframe.attr("src", Shiny.app.runtimeState.containerPath);
        $iframe.on("load", () => {
            const _shinyFrame = document.getElementById('shinyframe');
            const content = _shinyFrame.contentDocument.documentElement.textContent || _shinyFrame.contentDocument.documentElement.innerText;
            if (content === '{"status":"fail","data":"app_crashed"}' || content === '{\"status\":\"fail\",\"data\":\"app_stopped_or_non_existent\"}') {
                if (!Shiny.app.staticState.noAutomaticReloaded) {
                    Shiny.ui.showLoading();
                    const url = new URL(window.location);
                    url.searchParams.append("sp_automatic_reload", "true");
                    window.location = url;
                } else {
                    Shiny.ui.showCrashedPage();
                }
            }
            if (content === '{"status":"fail","data":"shinyproxy_authentication_required"}') {
                shinyProxy.ui.showLoggedOutPage();
            }
        });
        $('#iframeinsert').before($iframe); // insert the iframe into the HTML.
    },

    /**
     * Shows the loading page.
     */
    showLoading: function () {
        $('#appStopped').hide();
        $('#shinyframe').remove();
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
        waitForShinyFrameLoadingComplete(() => {
            $("#loading").fadeOut("slow", () => {
                $("#loadingAnimation").show();
                $("#loadAppError").hide();
            });
        });
    },

    showResumingPage: function () {
        $('#loading').hide();
        $('#shinyframe').hide();
        $("#resumingApp").show();
    },

    showStoppingPage: function () {
        $('#loading').hide();
        $('#shinyframe').hide();
        $('#modal').modal('hide')
        $("#stoppingApp").show();
    },

    showPausingPage: function () {
        $('#loading').hide();
        $('#shinyframe').hide();
        $('#modal').modal('hide')
        $("#pausingApp").show();
    },

    showPausedAppPage: function () {
        $('#shinyframe').remove();
        $('#loading').hide();
        $('#modal').modal('hide')
        $('#appPaused').show();
        $("#navbarWrapper").show();
    },

    showFailedToReloadPage: function () {
        $('#shinyframe').remove();
        $('#loading').hide();
        $("#reloadFailed").show();
        $("#navbarWrapper").show();
    },

    showStartFailedPage: function (errorMessage) {
        $('#shinyframe').hide();
        $('#loading').hide();
        $("#startFailed").show();
        $("#navbarWrapper").show();
        if (errorMessage) {
            $('#startFailedMessage').text(errorMessage).show();
        }
    },

    showStoppedPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        $('#shinyframe').remove();
        $('#loading').hide();
        $('#modal').modal('hide');
        $("#navbarWrapper").show();
        if (!$('#appCrashed').is(":visible")) {
            $('#appStopped').show();
        }
    },

    showCrashedPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        $('#shinyframe').remove();
        $("#loading").hide();
        $('#modal').modal('hide')
        $('#appCrashed').show();
        $("#navbarWrapper").show();
    },

    showTransferredPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        $('#shinyframe').remove();
        $('#loading').hide();
        $('#modal').modal('hide')
        $('#appTransferred').show();
        $("#navbarWrapper").show();
    },

    showLoggedOutPage: function () {
        Shiny.app.runtimeState.appStopped = true;
        if (!Shiny.app.runtimeState.navigatingAway) {
            // only show it when not navigating away, e.g. when logging out in the current tab
            $('#shinyframe').remove();
            $("#loading").hide();
            $('#modal').modal('hide')
            $("#navbar").hide();
            $('#userLoggedOut').show();
            $("#navbarWrapper").show();
        }
    },

    removeFrame() {
        $('#shinyframe').remove();
    },

    formatStatus(status) {
        if (status === "Up") {
            return `<span class="label status-label label-success">Up</span>`;
        }
        if (status === "New") {
            return `<span class="label status-label label-warning">New</span>`;
        }
        if (status === "Resuming") {
            return `<span class="label status-label label-warning">Resuming</span>`;
        }
        if (status === "Pausing") {
            return `<span class="label status-label label-warning">Pausing</span>`;
        }
        if (status === "Paused") {
            return `<span class="label status-label label-default">Paused</span>`;
        }
        if (status === "Stopping") {
            return `<span class="label status-label label-danger">Stopping</span>`;
        }
        if (status === "Stopped") {
            return `<span class="label status-label label-danger">Stopped</span>`;
        }
        return "";
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
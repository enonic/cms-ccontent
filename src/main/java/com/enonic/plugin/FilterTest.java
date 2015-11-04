package com.enonic.plugin;

import com.enonic.cms.api.plugin.ext.http.HttpResponseFilter;

import javax.servlet.http.HttpServletRequest;

/**
 * Created by rfo on 26/10/15.
 */
public class FilterTest extends HttpResponseFilter {
    @Override
    public String filterResponse(HttpServletRequest httpServletRequest, String s, String s1) throws Exception {
        return null;
    }
}

/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.cdi;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.logging.Logger;

import javax.enterprise.inject.AmbiguousResolutionException;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.util.AnnotationLiteral;

import com.vaadin.cdi.internal.AnnotationUtil;
import com.vaadin.cdi.internal.CDIUtil;
import com.vaadin.cdi.internal.ConventionsAccess;
import com.vaadin.cdi.internal.UIBean;
import com.vaadin.cdi.internal.VaadinUICloseEvent;
import com.vaadin.server.ClientConnector.DetachEvent;
import com.vaadin.server.ClientConnector.DetachListener;
import com.vaadin.server.DefaultUIProvider;
import com.vaadin.server.UIClassSelectionEvent;
import com.vaadin.server.UICreateEvent;
import com.vaadin.server.VaadinRequest;
import com.vaadin.ui.UI;
import com.vaadin.util.CurrentInstance;

public class CDIUIProvider extends DefaultUIProvider implements Serializable {

    private static final Annotation QUALIFIER_ANY = new AnnotationLiteral<Any>() {
    };
    // TODO a better way to do this could be custom injection management in the
    // Extension if feasible
    private BeanManager beanManager = null;

    public BeanManager getBeanManager() {
        if (beanManager == null) {
            getLogger()
                    .fine("CDIUIProvider is not injected, using JNDI lookup");
            // as the CDIUIProvider is not injected, need to use JNDI lookup
            beanManager = CDIUtil.lookupBeanManager();
        }
        return beanManager;
    }

    @Override
    public UI createInstance(UICreateEvent uiCreateEvent) {
        getLogger().fine("Creating new UI instance");
        Class<? extends UI> type = uiCreateEvent.getUIClass();
        int uiId = uiCreateEvent.getUiId();
        VaadinRequest request = uiCreateEvent.getRequest();
        Bean<?> bean = scanForBeans(type, request);
        UIBean uiBean = new UIBean(bean, uiId);
        try {
            // Make the UIBean available to UIScopedContext when creating nested
            // injected objects
            CurrentInstance.set(UIBean.class, uiBean);
            UI ui = (UI) getBeanManager().getReference(uiBean, type,
                    getBeanManager().createCreationalContext(bean));
            ui.addDetachListener(new DetachListenerImpl(getBeanManager()));
            return ui;
        } finally {
            CurrentInstance.set(UIBean.class, null);
        }
    }

    @Override
    public Class<? extends UI> getUIClass(UIClassSelectionEvent selectionEvent) {
        VaadinRequest request = selectionEvent.getRequest();
        String uiMapping = parseUIMapping(request);
        if (isRoot(request)) {
            return rootUI();
        }
        Bean<?> uiBean = getUIBeanWithMapping(uiMapping);

        if (uiBean != null) {
            return uiBean.getBeanClass().asSubclass(UI.class);
        }

        if (uiMapping.isEmpty()) {
            // See if UI is configured to web.xml with VaadinCDIServlet. This is
            // done only if no specific UI name is given.
            return super.getUIClass(selectionEvent);
        }

        return null;
    }

    boolean isRoot(VaadinRequest request) {
        String pathInfo = request.getPathInfo();

        if (pathInfo == null) {
            return false;
        }

        return pathInfo.equals("/");
    }

    Class<? extends UI> rootUI() {
        Set<Bean<?>> rootBeans = AnnotationUtil
                .getRootUiBeans(getBeanManager());
        if (rootBeans.isEmpty()) {
            return null;
        }
        if (rootBeans.size() > 1) {
            StringBuilder errorMessage = new StringBuilder();
            for (Bean<?> bean : rootBeans) {
                errorMessage.append(bean.getBeanClass().getName());
                errorMessage.append("\n");
            }
            throw new IllegalStateException(
                    "Multiple beans are declared as root UIs: "
                            + errorMessage.toString());
        }
        Bean<?> uiBean = rootBeans.iterator().next();
        Class<?> rootUI = uiBean.getBeanClass();
        return rootUI.asSubclass(UI.class);
    }

    protected Bean<?> getUIBeanWithMapping(String mapping) {
        Set<Bean<?>> beans = AnnotationUtil.getUiBeans(getBeanManager());

        for (Bean<?> bean : beans) {
            // We need this check since the returned beans can also be producers
            if (UI.class.isAssignableFrom(bean.getBeanClass())) {
                Class<? extends UI> beanClass = bean.getBeanClass().asSubclass(
                        UI.class);

                if(ConventionsAccess.uiClassIsValid(beanClass)) {
                    String computedMapping = ConventionsAccess
                            .deriveMappingForUI(beanClass);
                    if (mapping.equals(computedMapping)) {
                        return bean;
                    }
                }
            }
        }

        return null;
    }

    private Bean<?> scanForBeans(Class<? extends UI> type, VaadinRequest request) {
        BeanManager beanManager = getBeanManager();
        Bean<?> bean = null;
        Set<Bean<?>> beans = beanManager.getBeans(type, QUALIFIER_ANY);

        if (beans.isEmpty()) {
            getLogger().warning(
                    "Could not find UI bean for " + type.getCanonicalName());
            return null;
        } else {
            try {
                bean = beanManager.resolve(beans);
            } catch (AmbiguousResolutionException e) {
                bean = null;
            }
        }

        String uiMapping = "";
        if (bean == null) {
            if(ConventionsAccess.uiClassIsValid(type)) {
                uiMapping = parseUIMapping(request);
                bean = getUIBeanWithMapping(uiMapping);
            } else {
                throw new IllegalStateException("UI class: " + type.getName()
                        + " with mapping: " + uiMapping
                        + " is not a valid Vaadin CDI UI!");
            }
        }
        return bean;
    }

    String parseUIMapping(VaadinRequest request) {
        return parseUIMapping(request.getPathInfo());
    }

    String parseUIMapping(String requestPath) {
        if (requestPath != null && requestPath.length() > 1) {
            String path = requestPath;
            if (requestPath.endsWith("/")) {
                path = requestPath.substring(0, requestPath.length() - 1);
            }
            if (!path.contains("!")) {
                int lastIndex = path.lastIndexOf('/');
                return path.substring(lastIndex + 1);
            } else {
                int lastIndexOfBang = path.lastIndexOf('!');
                // strip slash with bank => /!
                String pathWithoutView = path.substring(0, lastIndexOfBang - 1);
                int lastSlashIndex = pathWithoutView.lastIndexOf('/');
                return pathWithoutView.substring(lastSlashIndex + 1);
            }
        }
        return "";
    }

    private static Logger getLogger() {
        return Logger.getLogger(CDIUIProvider.class.getCanonicalName());
    }

    public static final class DetachListenerImpl implements DetachListener {
        private BeanManager beanManager;

        public DetachListenerImpl(BeanManager beanManager) {
            this.beanManager = beanManager;
        }

        @Override
        public void detach(DetachEvent event) {
            Object source = event.getSource();
            if(source instanceof UI) {

                UI ui = (UI)source;
                beanManager.fireEvent(new VaadinUICloseEvent(CDIUtil
                        .getSessionId(ui.getSession()), ui.getUIId()));
            }

        }
    }
}

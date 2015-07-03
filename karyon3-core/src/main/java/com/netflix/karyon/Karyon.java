package com.netflix.karyon;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Stage;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.util.Modules;
import com.netflix.governator.DefaultModule;
import com.netflix.governator.ElementsEx;
import com.netflix.governator.LifecycleInjector;
import com.netflix.governator.LifecycleManager;
import com.netflix.governator.LifecycleModule;
import com.netflix.governator.ModuleListProvider;
import com.netflix.governator.ModuleListProviders;
import com.netflix.governator.ServiceLoaderModuleListProvider;
import com.netflix.governator.auto.AutoContext;
import com.netflix.governator.auto.Condition;
import com.netflix.governator.auto.DefaultPropertySource;
import com.netflix.governator.auto.ModuleProvider;
import com.netflix.governator.auto.PropertySource;
import com.netflix.governator.auto.annotations.Bootstrap;
import com.netflix.governator.auto.annotations.Conditional;
import com.netflix.governator.auto.annotations.ConditionalOnProfile;
import com.netflix.governator.auto.annotations.OverrideModule;

/**
 * Karyon is the core bootstrapper for a Guice based application with auto module loading
 * capabilities based on profiles and other conditionals.
 * 
 * Karyon takes the approach that the application entry point should only be responsible
 * for creating the Guice injector and wait for the application to shut down through any
 * shutdown mechanism.  All application services are specified using Guice modules, with
 * any require services simply being bound asEagerSingleton.  
 * 
<pre>
@{code
@Path("/")
public class HelloWorldApp extends DefaultLifecycleListener {
    public static void main(String[] args) throws InterruptedException {
        new Karyon()
            .addModules(
                 new JettyModule(),
                 new JerseyServletModule() {
                    @Override
                    protected void configureServlets() {
                        serve("/*").with(GuiceContainer.class);
                        bind(GuiceContainer.class).asEagerSingleton();
                        
                        bind(HelloWorldApp.class).asEagerSingleton();
                    }  
                }
            )
            .withConfigName("helloworld")
            .addBootstrapModule(new ArchaiusModule())
            .createInjector()
            .awaitTermination();
    }
    
    @GET
    public String sayHello() {
        return "hello world";
    }

    @Override
    public void onStarted() {
        System.out.println("Started ***** ");
    }
}
}
</pre>
 * @author elandau
 *
 */
public class Karyon {
    private static final Logger LOG = LoggerFactory.getLogger(Karyon.class);
    
    private Stage                       stage = Stage.DEVELOPMENT;
    private String                      configName = "application";
    private List<Module>                bootstrapModules = new ArrayList<>();
    private List<Module>                modules = new ArrayList<>();
    private Set<String>                 profiles = new HashSet<>();
    private List<ModuleListProvider>    moduleProviders = new ArrayList<>();

    /**
     * Module to add to the final injector
     * @param module
     * @return
     */
    public Karyon addModule(Module module) {
        this.modules.add(module);
        return this;
    }
    
    /**
     * Modules to add to the final injector
     * @param modules
     * @return
     */
    public Karyon addModules(Module... modules) {
        this.modules.addAll(Arrays.asList(modules));
        return this;
    }

    /**
     * Configuration name to use for property loading.  Default configuration
     * name is 'application'.  This value is injectable as
     *  
     *      @Named("karyon.configName") String configName
     * 
     * @param value
     * @return
     */
    public Karyon withConfigName(String value) {
        this.configName = value;
        return this;
    }
    
    /**
     * Add a module finder such as a ServiceLoaderModuleFinder or ClassPathScannerModuleFinder
     * @param finder
     * @return
     */
    public Karyon addModuleListProvider(ModuleListProvider finder) {
        this.moduleProviders.add(finder);
        return this;
    }
    
    /**
     * Bootstrap overrides for the bootstrap injector used to load and inject into 
     * the conditions.  Bootstrap does not restrict the bindings to allow any type
     * to be externally provided and injected into conditions.  Several simple
     * bindings are provided by default and may be overridden,
     * 1.  Config
     * 2.  Profiles
     * 3.  BoundKeys (TODO)
     * 
     * @param bootstrapModule
     */
    public Karyon addBootstrapModule(Module bootstrapModule) {
        this.bootstrapModules.add(bootstrapModule);
        return this;
    }
    
    public Karyon addBootstrapModules(Module ... bootstrapModule) {
        this.bootstrapModules.addAll(Arrays.asList(bootstrapModule));
        return this;
    }

    public Karyon addBootstrapModules(List<Module> bootstrapModule) {
        this.bootstrapModules.addAll(bootstrapModule);
        return this;
    }

    /**
     * Add a runtime profile.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfile(String profile) {
        this.profiles.add(profile);
        return this;
    }

    /**
     * Add a runtime profiles.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfiles(String... profiles) {
        this.profiles.addAll(Arrays.asList(profiles));
        return this;
    }
    
    /**
     * Add a runtime profiles.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfiles(Collection<String> profiles) {
        this.profiles.addAll(profiles);
        return this;
    }

    public LifecycleInjector createInjector() {
        LOG.info("Using profiles : " + profiles);
        
        bootstrapModules.add(createInternalBootstrapModule());
        
        // If no loader has been specified use the default which is to load
        // all Module classes via the ServiceLoader
        if (moduleProviders.isEmpty()) {
            moduleProviders.add(new ServiceLoaderModuleListProvider());
        }
        addModuleListProvider(ModuleListProviders.forPackagesConditional("com.netflix.karyon"));
        
        // Generate a single list of all discovered modules
        // TODO: Duplicates?
        final Set<Module> loadedModules   = new HashSet<>();
        for (ModuleListProvider loader : moduleProviders) {
            loadedModules.addAll(loader.get());
        }

        final LifecycleManager manager = new LifecycleManager();
        
        Injector injector;
        try {
            injector = Guice.createInjector(
                stage, 
                new LifecycleModule(), 
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(LifecycleManager.class).toInstance(manager);
                        requestInjection(manager);
                    }
                }, 
                create(
                    manager,
                    loadedModules, 
                    modules, 
                    false, 
                    // First, auto load the bootstrap modules (usually deal with configuration and logging) and
                    // use to load the main module.
                    create(
                        manager,
                        loadedModules, 
                        bootstrapModules, 
                        true, 
                        new DefaultModule() {
                            @Provides
                            PropertySource getPropertySource() {
                                return new DefaultPropertySource(); 
                            }
                        })));
        }
        catch (Exception e) {
            try {
                manager.notifyStartFailed(e);
            }
            catch (Exception e2) {
                
            }
            throw e;
        }
        
        try {
            manager.notifyStarted();
            return new LifecycleInjector(injector, manager);
        }
        catch (Exception e) {
            manager.notifyShutdown();
            throw e;
        }
    }
    
    private String formatConditional(Annotation a) {
        String str = a.toString();
        int pos = str.indexOf("(");
        if (pos != -1) {
            pos = str.lastIndexOf(".", pos);
            if (pos != -1) {
                return str.substring(pos+1);
            }
        }
        return str;
    }
    
    private boolean evaluateConditions(Injector injector, Module module) throws Exception {
        LOG.info("Evaluating module {}", module.getClass().getName());
        
        // The class may have multiple Conditional annotations
        for (Annotation annot : module.getClass().getAnnotations()) {
            Conditional conditional = annot.annotationType().getAnnotation(Conditional.class);
            if (conditional != null) {
                // A Conditional may have a list of multiple Conditions
                for (Class<? extends Condition> condition : conditional.value()) {
                    try {
                        // Construct the condition using Guice so that anything may be injected into 
                        // the condition
                        Condition c = injector.getInstance(condition);
                        // Look for method signature : boolean check(T annot)
                        // where T is the annotation type.  Note that the same checker will be used 
                        // for all conditions of the same annotation type.
                        try {
                            Method check = condition.getDeclaredMethod("check", annot.annotationType());
                            if (!(boolean)check.invoke(c, annot)) {
                                LOG.info("  - {}", formatConditional(annot));
                                return false;
                            }
                        }
                        // If not found, look for method signature 
                        //      boolean check();
                        catch (NoSuchMethodException e) {
                            Method check = condition.getDeclaredMethod("check");
                            if (!(boolean)check.invoke(c)) {
                                LOG.info("  - {}", formatConditional(annot));
                                return false;
                            }
                        }
                        
                        LOG.info("  + {}", formatConditional(annot));
                    }
                    catch (Exception e) {
                        LOG.info("  - {}", formatConditional(annot));
                        throw new Exception("Failed to check condition '" + condition + "' on module '" + module.getClass() + "'");
                    }
                }
            }
        }
        return true;
    }
    
    private boolean isEnabled(PropertySource propertySource, String name) {
        int pos = name.length();
        do {
            if (propertySource.get("governator.module.disabled." + name.substring(0, pos), Boolean.class, false)) {
                return false;
            }
            pos = name.lastIndexOf(".", pos-1);
        } while (pos > 0);
        return true;
    }
    
    private Module create(final LifecycleManager manager, final Collection<Module> loadedModules, final List<Module> rootModules, final boolean isBootstrap, final Module bootstrapModule) {
        // Populate all the bootstrap state from the main module
        final List<Element> elements    = Elements.getElements(Stage.DEVELOPMENT, rootModules);
        final Set<Key<?>>   keys        = ElementsEx.getAllInjectionKeys(elements);
        final List<String>  moduleNames = ElementsEx.getAllSourceModules(elements);
        
        final Injector injector = Guice.createInjector(
            stage, 
            new LifecycleModule(), 
            new AbstractModule() {
                @Override
                protected void configure() {
                    bind(LifecycleManager.class).toInstance(manager);
                    requestInjection(manager);
                }
            }, 
            Modules
                .override(new DefaultModule() {
                    @Provides
                    public AutoContext getContext() {
                        return new AutoContext() {
                            @Override
                            public boolean hasProfile(String profile) {
                                return profiles.contains(profile);
                            }
        
                            @Override
                            public boolean hasModule(String className) {
                                return moduleNames.contains(className);
                            }
                            
                            @Override
                            public boolean hasBinding(Key<?> key) {
                                return keys.contains(key);
                            }
                        };
                    }
                })
                .with(bootstrapModule));

        PropertySource propertySource = injector.getInstance(PropertySource.class);
        
        // Iterate through all loaded modules and filter out any modules that
        // have failed the condition check.  Also, keep track of any override modules
        // for already installed modules.
        final List<Module> overrideModules = new ArrayList<>();
        final List<Module> moreModules     = new ArrayList<>();
        for (Module module : loadedModules) {
            if (!isEnabled(propertySource, module.getClass().getName())) {
                LOG.info("Ignoring module {}", module.getClass().getName());
                continue;
            }
            
            try {
                Bootstrap bs = module.getClass().getAnnotation(Bootstrap.class);
                if (isBootstrap == (bs != null) && evaluateConditions(injector, module)) {
                    OverrideModule override = module.getClass().getAnnotation(OverrideModule.class);
                    if (override != null) {
                        if (moduleNames.contains(override.value().getName())) {
                            LOG.info("    Adding override module {}", module.getClass().getSimpleName());
                            overrideModules.add(module);
                        }
                    }
                    else {
                        LOG.info("    Adding conditional module {}", module.getClass().getSimpleName());
                        moreModules.add(module);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        final List<Module> extModules     = new ArrayList<>();
        List<Binding<ModuleProvider>> moduleProviders = injector.findBindingsByType(TypeLiteral.get(ModuleProvider.class));
        for (Binding<ModuleProvider> binding : moduleProviders) {
            Module module = binding.getProvider().get().get();
            LOG.info("Adding exposed bootstrap module {}", module.getClass().getName());
            extModules.add(module);
        }

        return Modules
            .override(new AbstractModule() {
                @Override
                protected void configure() {
                    install(Modules.combine(rootModules));
                    install(Modules.combine(moreModules));
                }
            })
            .with(Modules
                .override(overrideModules)
                .with(Modules.combine(extModules)))
            ;
    }
    
    private Module createInternalBootstrapModule() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                this.bindConstant().annotatedWith(Names.named("karyon.configName")).to(configName);
            }
        };
    }
}
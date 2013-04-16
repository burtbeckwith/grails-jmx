import javax.sql.DataSource

import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.codehaus.groovy.grails.commons.GrailsServiceClass
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.jmx.export.MBeanExporter
import org.springframework.jmx.export.annotation.AnnotationJmxAttributeSource
import org.springframework.jmx.export.annotation.ManagedResource
import org.springframework.jmx.export.assembler.MetadataMBeanInfoAssembler
import org.springframework.jmx.export.assembler.MethodExclusionMBeanInfoAssembler
import org.springframework.jmx.export.naming.MetadataNamingStrategy
import org.springframework.jmx.support.MBeanServerFactoryBean

class JmxGrailsPlugin {
	def version = '0.8'
	def grailsVersion = '2.0 > *'
	def loadAfter = ['hibernate']
	def author = 'Burt Beckwith'
	def authorEmail = 'beckwithb@vmware.com'
	def title = 'JMX Plugin'
	def description = 'Adds JMX support and provides the ability to expose services and other Spring beans as MBeans'
	def documentation = 'http://grails.org/plugin/jmx'

	String license = 'APACHE'
	def issueManagement = [system: 'JIRA', url: 'http://jira.grails.org/browse/GPJMX']
	def developers = [[name: 'Ken Sipe', email: 'kensipe@gmail.com']]
	def scm = [url: 'https://github.com/burtbeckwith/grails-jmx']

	private final Logger log = LoggerFactory.getLogger('grails.plugin.jmx.JmxGrailsPlugin')

	private static final String DEFAULT_EXCLUDE_METHODS =
		'isTransactional,setTransactional,getTransactional,' +
		'getJmxexpose,setJmxexpose,' +
		'getExpose,setExpose,' +
		'getMetaClass,setMetaClass,' +
		'getScope,setScope'

	def doWithSpring = {

		// adding the mbean server configuration and export with mbeanserver ref... no exports
		mbeanServer(MBeanServerFactoryBean) {
			locateExistingServerIfPossible = true
		}

		mbeanExporter(MBeanExporter) {
			server = ref('mbeanServer')
			beans = [:]
		}

		// allows the use of annotations for attributes/operations
		jmxAnnotationAttributeSource(AnnotationJmxAttributeSource)

		jmxAnnotationAssembler(MetadataMBeanInfoAssembler) {
			attributeSource = ref('jmxAnnotationAttributeSource')
		}

		// an exporter that uses annotations
		jmxAnnotationMBeanExporter(MBeanExporter) { bean ->
			bean.lazyInit = false
			assembler = ref('jmxAnnotationAssembler')
			server = ref('mbeanServer')
			beans = [:]
			autodetectMode = MBeanExporter.AUTODETECT_ASSEMBLER // don't autodetect mbeans from other exporter
			namingStrategy = ref('jmxAnnotationNamingStrategy')
		}

		jmxAnnotationNamingStrategy(MetadataNamingStrategy) {
			attributeSource = ref('jmxAnnotationAttributeSource')
			defaultDomain = application.metadata['app.name']
		}

		registerHibernateStatisticsService.delegate = delegate
		registerHibernateStatisticsService()

		registerLog4jMBeans.delegate = delegate
		registerLog4jMBeans()
	}

	private registerHibernateStatisticsService = { conf ->
		try {
			Class.forName('org.hibernate.jmx.StatisticsService', true, Thread.currentThread().contextClassLoader)

			hibernateStatsMBean(org.hibernate.jmx.StatisticsService) {
				sessionFactory = ref('sessionFactory')
			}
		}
		catch (e) {
			// Hibernate isn't available
		}
	}

	private registerLog4jMBeans = { conf ->
		try {
			Class.forName('org.apache.log4j.jmx.HierarchyDynamicMBean', true, Thread.currentThread().contextClassLoader)

			log4jMBean(org.apache.log4j.jmx.HierarchyDynamicMBean)
		}
		catch (e) {
			// Log4j isn't available
		}
	}

	def doWithApplicationContext = { ctx ->

		String domain = application.metadata['app.name']

		MBeanExporter exporter = ctx.mbeanExporter
		// we probably need to create our own assembler...
		//exporter.assembler = new org.springframework.jmx.export.assembler.SimpleReflectiveMBeanInfoAssembler()

		// exporting mbeans
		exportConfigBeans(exporter, ctx, domain)
		exportLogger(ctx, exporter, domain)
		exportServices(application, exporter, domain, ctx)
		exportConfiguredObjects(application, exporter, domain, ctx)

		registerMBeans(exporter)
	}

	private void exportLogger(ApplicationContext ctx, MBeanExporter exporter, String domain) {
		if (!ctx.containsBean('log4jMBean')) return

		def hierarchyBean = ctx.log4jMBean
		exporter.beans."${domain}:service=log4j,type=configuration" = hierarchyBean

		hierarchyBean.addLoggerMBean org.apache.log4j.Logger.rootLogger.name
	}

	private void exportServices(GrailsApplication application, MBeanExporter exporter, String domain, ctx) {
		Properties excludeMethods = new Properties()

		for (GrailsServiceClass serviceClass in application.serviceClasses) {

			if (AnnotationUtils.findAnnotation(serviceClass.clazz, ManagedResource)) {
				log.debug "Skipping auto-registration of $serviceClass.clazz.name since it's annotated with @ManagedResource and will be registered via metadata"
				continue
			}

			exportClass exporter, domain, ctx, serviceClass.clazz, serviceClass.shortName,
				serviceClass.propertyName, excludeMethods, 'service'
		}

		handleExcludeMethods(exporter, excludeMethods)
	}

	private void handleExcludeMethods(MBeanExporter exporter, Properties excludeMethods) {
		if (!excludeMethods) {
			return
		}

		exporter.assembler = new MethodExclusionMBeanInfoAssembler(ignoredMethodMappings: excludeMethods)
	}

	private void exportClass(MBeanExporter exporter, String domain, ctx, Class serviceClass, String serviceName,
	                         String propertyName, Properties excludeMethods, String type) {

		def exposeList = GrailsClassUtils.getStaticPropertyValue(serviceClass, 'expose')
		def jmxExposed = exposeList?.find { it.startsWith('jmx') }
		if (!jmxExposed) {
			return
		}

		def scope = GrailsClassUtils.getStaticPropertyValue(serviceClass, 'scope')

		boolean singleton = (scope == null || scope != 'singleton')
		if (!singleton) {
			return
		}

		def exposeMap = GrailsClassUtils.getStaticPropertyValue(serviceClass, 'jmxexpose')
		if (exposeMap == null) {
			exposeMap = [excludeMethods: DEFAULT_EXCLUDE_METHODS]
		}

		// change service name if provided by jmx:objectname
		String objectName = "$type=$serviceName,type=$type"
		def m = jmxExposed =~ 'jmx:(.*)'
		if (m) {
			objectName = "${m[0][1]}"
		}

		if (exposeMap.excludeMethods) {
			excludeMethods.setProperty("${domain}:${objectName}", exposeMap.excludeMethods)
		}

		exporter.beans."${domain}:${objectName}" = ctx.getBean(propertyName)
		log.debug "Auto-registered service $propertyName as a JMX MBean"
	}

	private void exportConfiguredObjects(GrailsApplication application, MBeanExporter exporter, String domain, ctx) {
		// example config:
		/*
			grails {
				jmx {
					exportBeans = ['myBeanOne', 'myBeanTwo']
				}
			}
		*/
		def configuredObjectBeans = application.config.grails.jmx.exportBeans
		if (!configuredObjectBeans) {
			return
		}

		if (configuredObjectBeans instanceof String) {
			// allow list or single class, e.g.
			//     exportBeans = ['myBeanOne', 'myBeanTwo']
			//      ... or ...
			//     exportBeans = 'myBeanOne'

			configuredObjectBeans = [configuredObjectBeans]
		}

		Properties excludeMethods = new Properties()

		for (String jmxBeanName in configuredObjectBeans) {
			def bean = ctx.getBean(jmxBeanName)
			Class jmxServiceClass = bean.getClass()
			String serviceName = jmxServiceClass.simpleName

			exportClass exporter, domain, ctx, jmxServiceClass, serviceName, jmxBeanName, excludeMethods, 'utility'
		}

		handleExcludeMethods(exporter, excludeMethods)
	}

	private void registerMBeans(MBeanExporter exporter) {
		exporter.unregisterBeans()
		exporter.registerBeans()
	}

	private void exportConfigBeans(MBeanExporter exporter, ApplicationContext ctx, String domain) {
		if (ctx.containsBean('hibernateStatsMBean')) {
			exporter.beans."${domain}:service=hibernate,type=configuration" = ctx.hibernateStatsMBean
		}

		ctx.getBeansOfType(DataSource).each { String name, DataSource bean ->
			if (name.indexOf('Unproxied') > -1 || !(bean instanceof TransactionAwareDataSourceProxy)) {
				exporter.beans."${domain}:service=${name - 'Unproxied'},type=configuration" = bean
			}
		}
	}

	def onConfigChange = { event ->
		// todo: potentially unregister a plugin
	}
}

import org.springframework.jmx.export.MBeanExporter
import org.springframework.jmx.export.assembler.MethodExclusionMBeanInfoAssembler
import org.springframework.jmx.support.MBeanServerFactoryBean

import org.codehaus.groovy.grails.commons.GrailsClassUtils as GCU
import org.codehaus.groovy.grails.commons.GrailsServiceClass

import org.apache.log4j.jmx.HierarchyDynamicMBean
import org.apache.log4j.Logger

class JmxGrailsPlugin {
	def version = '0.8'
	def grailsVersion = '2.0 > *'
	def loadAfter = ['hibernate']
	def author = 'Ken Sipe'
	def authorEmail = 'kensipe@gmail.com'
	def title = 'JMX Plugin'
	def description = 'Adds JMX support to a Grails application. Provides ability to expose services as MBeans'
	def documentation = 'http://grails.org/plugin/jmx'

	String license = 'APACHE'
	def issueManagement = [system: 'JIRA', url: 'http://jira.grails.org/browse/GPJMX']
	def developers = [[name: 'Burt Beckwith', email: 'beckwithb@vmware.com']]
	def scm = [url: 'https://github.com/burtbeckwith/grails-jmx']

	def doWithSpring = {

		// adding the mbean server configuration and export with mbeanserver ref... no exports
		mbeanServer(MBeanServerFactoryBean) {
			locateExistingServerIfPossible = true
		}

		if (manager?.hasGrailsPlugin('hibernate')) {
			hibernateStats(org.hibernate.jmx.StatisticsService) {
				sessionFactory = ref('sessionFactory')
			}
		}

		log4j(HierarchyDynamicMBean)

		exporter(MBeanExporter) {
			server = ref(mbeanServer)
			beans = [:]
		}
	}

	def doWithApplicationContext = { ctx ->

		String domain = application.metadata['app.name']

		MBeanExporter exporter = ctx.exporter
		// we probably need to create our own assembler...
		//exporter.assembler = new org.springframework.jmx.export.assembler.SimpleReflectiveMBeanInfoAssembler()

		// exporting mbeans
		exportConfigBeans(exporter, ctx, domain)
		exportLogger(ctx, exporter, domain)
		exportServices(application, exporter, domain, ctx)
		exportConfiguredObjects(application, exporter, domain, ctx)

		registerMBeans(exporter)
	}

	private void exportLogger(ctx, MBeanExporter exporter, domain) {
		HierarchyDynamicMBean logMBean = ctx.log4j
		exporter.beans."${domain}:service=log4j,type=configuration" = logMBean
		logMBean.addLoggerMBean(Logger.rootLogger.name)
	}

	private void exportServices(application, MBeanExporter exporter, String domain, ctx) {
		Properties excludeMethods = new Properties()

		for (GrailsServiceClass serviceClass in application.serviceClasses) {
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

	private void exportClass(MBeanExporter exporter, String domain, ctx, serviceClass, String serviceName, String propertyName,
				Properties excludeMethods, String type) {

		String objectName = "$type=$serviceName,type=$type"

		def exposeList = GCU.getStaticPropertyValue(serviceClass, 'expose')
		def exposeMap = GCU.getStaticPropertyValue(serviceClass, 'jmxexpose')
		def scope = GCU.getStaticPropertyValue(serviceClass, 'scope')

		def jmxExposed = exposeList?.find { it.startsWith('jmx') }
		boolean singleton = (scope == null || scope != 'singleton')

		if (jmxExposed && singleton) {
			// change service name if provided by jmx:objectname
			def m = jmxExposed =~ 'jmx:(.*)'
			if (m) {
				objectName = "${m[0][1]}"
			}
			if (exposeMap != null && exposeMap['excludeMethods']) {
				excludeMethods.setProperty("${domain}:${objectName}", exposeMap['excludeMethods'])
			}

			exporter.beans."${domain}:${objectName}" = ctx.getBean(propertyName)
		}
	}

	private void exportConfiguredObjects(application, MBeanExporter exporter, String domain, ctx) {
		// example config:
		/*
			grails {
				jmx {
					exportBeans = ['myBeanOne', 'myBeanTwo']
				}
			}
		*/
		def configuredObjectBeans = application.config.grails.jmx.exportBeans

		if (configuredObjectBeans) {
			if (configuredObjectBeans instanceof String) {
				// allow list or single class, e.g.
				//     exportBeans = ['myBeanOne', 'myBeanTwo']
				//      ... or ...
				//     exportBeans = 'myBeanOne'

				configuredObjectBeans = [configuredObjectBeans]
			}

			Properties excludeMethods = new Properties()

			configuredObjectBeans.each { jmxBeanName ->

				def bean = ctx.getBean(jmxBeanName)
				def jmxServiceClass = bean.getClass()
				def serviceName = jmxServiceClass.simpleName

				exportClass exporter, domain, ctx, jmxServiceClass, serviceName,
					jmxBeanName, excludeMethods, 'utility'
			}

			handleExcludeMethods(exporter, excludeMethods)
		}
	}

	private void registerMBeans(MBeanExporter exporter) {
		exporter.unregisterBeans()
		exporter.registerBeans()
	}

	private void exportConfigBeans(MBeanExporter exporter, ctx, String domain) {
		if (ctx.hibernateStats) {
			exporter.beans."${domain}:service=hibernate,type=configuration" = ctx.hibernateStats
		}

		// TODO: should check for database plugin
		exporter.beans."${domain}:service=datasource,type=configuration" = ctx.dataSource
	}

	def onConfigChange = { event ->
		// todo: potentially unregister a plugin
	}
}

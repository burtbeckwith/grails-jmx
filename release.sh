rm -rf target/release
mkdir target/release
cd target/release
git clone git@github.com:burtbeckwith/grails-jmx.git
cd grails-jmx
grails clean
grails compile
grails compile

#grails publish-plugin --snapshot --stacktrace
grails publish-plugin --stacktrace
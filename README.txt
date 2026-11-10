At start up on windows, run the following command in the terminal:
mvn install:install-file -Dfile="C:\Program Files\BlueJ\lib\bluej.jar" "-DgroupId=org.bluej" "-DartifactId=bluej" "-Dversion=6.0.1" "-Dpackaging=jar"

Once that command is run, you can then run: mvn clean test.

To get access to the jar file, run: mvn package.

To install the extension, copy the jar file that will be generated in the target
directory and paste it inside of the bluej extension folder which can be found in most cases
at C:/Program Files/Bluej/lib/extension2.

Once installed, you can test out the extensions features in the bluej application.

Note on architecture. The three design patterns implemented are:
Observer, Builder, and Strategy.

Observer is used to notify analytic components whenever telemetry events are recorded.
Builder is used to construct the session snapshot for reporting.
Strategy is used to separate pdf and json report generation algorithm.
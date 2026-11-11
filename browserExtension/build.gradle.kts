plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    js(IR) {
        browser { commonWebpackConfig { outputFileName = "popup.js" } }
        binaries.executable()
    }
}

// Use compiler output directly; the popup needs neither npm packages nor webpack.
tasks.register<Sync>("packageHandoff") {
    dependsOn("jsProductionExecutableCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")) { include("*.js") }
    from("src/jsMain/resources")
    into(layout.buildDirectory.dir("dist/js/productionExecutable"))
}

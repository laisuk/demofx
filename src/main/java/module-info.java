module org.example.demofx {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.datatransfer;
    requires java.desktop;
    requires java.sql;
    requires OpenCC.Java;

    opens org.example.demofx to javafx.fxml;
    exports org.example.demofx;

}
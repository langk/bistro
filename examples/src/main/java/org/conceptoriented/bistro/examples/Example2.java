package org.conceptoriented.bistro.examples;

import org.conceptoriented.bistro.core.*;

import java.io.*;

public class Example2 {

    public static String location = "src/main/resources/ds1";

    public static Schema schema;

    public static void main(String[] args) throws IOException {

        //
        // Create schema
        //

        schema = new Schema("Example 2");

        //
        // Create tables and columns by loading data from CSV files
        //

        Table columnType = schema.getTable("Object");

        Table items = ExUtils.readFromCsv(schema, location, "OrderItems.csv");

        Table products = ExUtils.readFromCsv(schema, location, "Products.csv");

        //
        // Calculate amount
        //

        // [OrderItems].[Amount] = [Quantity] * [Unit Price]
        Column itemsAmount = schema.createColumn("Amount", items, columnType);
        itemsAmount.calc(
                p -> Double.valueOf((String)p[0]) * Double.valueOf((String)p[1]),
                items.getColumn("Quantity"), items.getColumn("Unit Price")
        );

        //
        // Link from OrderItems to Products
        //

        // [OrderItems].[Product]: OrderItems -> Products
        Column itemsProduct = schema.createColumn("Product", items, products);
        itemsProduct.link(
                new Column[] { items.getColumn("Product ID") },
                products.getColumn("ID")
        );

        //
        // Accumulate item characteristics
        //

        // [Products].[Total Amount] = SUM [OrderItems].[Amount]
        Column productsAmount = schema.createColumn("Total Amount", products, columnType);
        productsAmount.setDefaultValue(0.0); // It will be used as an initial value
        productsAmount.accu(
                itemsProduct,
                p -> (double)p[0] + (double)p[1], // [Amount] + [out]
                items.getColumn("Amount")
        );

        //
        // Evaluate and read values
        //

        schema.eval();

        Object value;

        value = itemsAmount.getValue(5); // value = 270.0 = 15 * 18
        value = itemsAmount.getValue(21); // value = 450.0 = 25 * 18

        value = itemsProduct.getValue(5); // value = 0
        value = itemsProduct.getValue(21); // value = 0

        value = productsAmount.getValue(0); // value = 720.0 = 270.0 + 450.0
    }

}

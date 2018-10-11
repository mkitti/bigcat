# BIGCAT (working title)

## Install

BigCAT is available on conda:
```shell
conda install -c hanslovsky bigcat
```
Alternatively, you can run the `install` script.

## Compile

To compile a "fat jar" with all dependencies added, run:

```shell
mvn clean compile assembly:single
```

## Run

```shell
java -Xmx16G -jar target/bigcat-0.0.1-SNAPSHOT-jar-with-dependencies.jar -i <input_hdf_file>
```

## Development

[![Join the chat at https://gitter.im/saalfeldlab/bigcat](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/saalfeldlab/bigcat?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
Collaborative volume annotation and segmentation with BigDataViewer



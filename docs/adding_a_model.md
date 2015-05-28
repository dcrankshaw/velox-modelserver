#Guide to creating a new Velox Application

There are two ways to deploy a model in Velox. This is our attempt
at making simple things simple, and complex things possible (Alan Kay).

First can be done purely at runtime.

The user must specify each of the feature models to be used as a map:

```python
matrixfact_config = {
        'onlineUpdateDelayInMillis': 5000,
        'batchRetrainDelayInMillis': 500000,
        'dimensions': 50,
        'modelClass': 'edu.berkeley.veloxms.models.MatrixFactorizationModel',
        }

newsgroups_config = {
        'onlineUpdateDelayInMillis': 5000,
        'batchRetrainDelayInMillis': 50000000,
        'dimensions': 20,
        'trainPath': 's3n://20newsgroups/',
        'modelClass': 'edu.berkeley.veloxms.models.NewsgroupsModel',
        }

config = {
        'sparkMaster': "local[2]",
        'sparkDataLocation': "/Users/tomerk11/Desktop/velox-data",
        'models': {
                'article_recommendation': {
                    'collab-features': json.dumps(matrixfact_config),
                    'topic-dist': json.dumps(newsgroups_config)
                },
                'fraud_detection': {
                    'clustering': json.dumps(clustering_config),
                    'svm': json.dumps(svm_config)
                }
        }
}

```

There are a few things going on here. The high level structure of the config contains
Velox cluster-wide settings, such as the location of the Spark cluster. It then contains
a dictionary of named models to deploy. The keys in this model dict are the names of the
models, and the values are another dict listing the features of that model. A model
configured in this way may have multiple features (that can have additional configuration
parameters), as long as each feature model takes the same input type. That input type
can be something simple like Int or String, or it may be a more complex user-defined type.
However, the type must be simple enough that it can be deserialized from a corresponding
JSON structure.


On the other hand, we also support a more complex way of creating a model. In order to
support a library of common feature models (e.g. topic modeling, collaborative filtering,
object detection, etc.), you can also specify a deserializer along with your feature models.
For example, if you wanted to predict CTR of web pages, you might have a topic model that does
great on plain-text documents, as well as a model that does object recognition in images.
Rather than having to rewrite the models to take an input of type `WebPage`,
which might contain both `String` and `Image`s, or might be sent over the
wire in some binary format, you can specify a transformation function
for each feature model that must have a signature of `(T) => U` where `T` is
the arbitrary input received as part of the JSON request (`WebPage` in the example)
and `U` is the input type expected by that feature function (`String` for
topic model and `Image` for the object detection model). This allows you
to combine multiple feature models that expect different inputs into a single
model without having to modify the feature models themselves for each application.


## Adding a new feature model

```scala

case class MyInputType(text: String, viewDuration: Long)

case class Cluster(center: Array[Double], radius: Array[Double], dimensions: Int, size: Int)

class MyModel extends FeatureModel[MyInputType] {

    //////////////////////////////////////////////////////////
    ///                 BROADCAST VARIABLES                ///
    //////////////////////////////////////////////////////////
    val clusters = broadcast[List[Cluster]]("clusters")
    val itemStorage = broadcast[Map[Long, FeatureVector]]("items")


    def computeFeatures(data: MyInputType): FeatureVector = {


    }



}


```



Assume we have some library of existing feature models. A feature model implementation looks
something like the code listing below.

```scala
case class MyInputType(text: String, viewDuration: Long)

class MyModel extends FeatureModel[MyInputType] {

    //////////////////////////////////////////////////////////
    ///                 BROADCAST VARIABLES                ///
    //////////////////////////////////////////////////////////
    val trainedSVM = broadcast[MySVM[MyInputType]]("svm")

    def computeFeatures(data: MyInputType): FeatureVector = {
        trainedSVM.get.predict(data)
    }

    def trainModel(
        observations: RDD[(UserID, MyInputType, Double)])
      : RDD[(UserID, FeatureVector)] = {

        val newSVM = SVM.SGDTrain(observations, iterations)
        trainedSVM.put(newSVM)
        observations.map({case(uid, example, score) => (uid, newSVM.predict(example))})
    }
}
```

Notice that this model has a concrete (non-generic) type of `FeatureModel[MyInputType]`. The type
parameter to `FeatureModel` indicates the input type this model expects. Thus, for every
feature model implementation, we know its expected input type at compile time.

Ideally, to deploy a model, a user would specify in a configuration file that the model should
be composed of 




Problems:

+ We don't know the expected input type of a model until runtime. This makes it extremely difficult to
have generic models. We want models to be generic because we don't care about the model input type in
Velox. That is all taken care of by the user code. The only restriction here is that all feature models
of given model must have the same input type.
+ As models get more complex, users are eventually going to want to create their applications
programmatically using an API. This would solve some problems, because a model's input type would
be known at compile time. Here we run into deployment problems though. It's not clear
how to deploy a Velox application in this case.
+ It seems like I have a couple conflicting goals here.
    + The first is to make deployment simple.
    This means that serving existing models should be able to be done via configuration. This is
    where I am running into problems with reflection.
    + Second, I want to increase the flexibility of what a model looks like. I'd like to allow
    for multiple feature models to be combined into a single ensemble.
